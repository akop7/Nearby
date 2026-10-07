package org.nearby.mesh.noise

import org.nearby.mesh.crypto.NodeIdentity
import org.nearby.mesh.protocol.MeshPacket
import org.nearby.mesh.protocol.PacketType
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages active cryptographic Noise_XX sessions indexed by peer nodeId.
 * Provides mutual authentication, symmetric key derivation, and forward secrecy per session.
 */
class NoiseSessionManager(
    private val localIdentity: NodeIdentity
) {
    private val sessions = ConcurrentHashMap<String, NoiseSession>()
    private val secureRandom = SecureRandom()

    // Key material to use for local static identity:
    // Uses privateKeyBytes if available, or derives private key material
    private val localStaticPrivKey: ByteArray by lazy {
        localIdentity.privateKeyBytes ?: NoiseCrypto.sha256(localIdentity.publicKeyBytes + localIdentity.nodeId.toByteArray())
    }

    private val localStaticPubKey: ByteArray by lazy {
        NoiseCrypto.getPublicKeyFromPrivateKey(localStaticPrivKey)
    }

    /**
     * Retrieves or creates a session for the given peer.
     */
    fun getOrCreateSession(peerNodeId: String, isInitiator: Boolean): NoiseSession {
        return sessions.computeIfAbsent(peerNodeId) {
            NoiseSession(
                peerNodeId = peerNodeId,
                isInitiator = isInitiator,
                localStaticPrivateKey = localStaticPrivKey,
                localStaticPublicKey = localStaticPubKey
            )
        }
    }

    fun getSession(peerNodeId: String): NoiseSession? = sessions[peerNodeId]

    fun isSessionEstablished(peerNodeId: String): Boolean {
        return sessions[peerNodeId]?.isHandshakeComplete == true
    }

    fun getSasCode(peerNodeId: String): String? {
        return sessions[peerNodeId]?.sasCode
    }

    fun removeSession(peerNodeId: String) {
        sessions.remove(peerNodeId)
    }

    /**
     * Initiates a Noise_XX handshake with [peerNodeId], returning Message 1 in a [MeshPacket].
     * If a handshake is already complete or in progress, avoids re-invoking writeMessage1 on dirty state.
     * Returns null if no packet needs to be transmitted.
     */
    @Synchronized
    fun initiateHandshake(peerNodeId: String, forceRestart: Boolean = false): MeshPacket? {
        val session = getOrCreateSession(peerNodeId, isInitiator = true)

        if (session.isHandshakeComplete && !forceRestart) {
            return null
        }

        if (session.isHandshakeInProgress && !forceRestart) {
            // Handshake is already in flight; do not re-invoke writeMessage1 on dirty state machine
            return null
        }

        // If step is not clean INITIATOR_SEND_MSG1, reset cleanly before writeMessage1
        if (session.handshake.step != NoiseHandshake.HandshakeStep.INITIATOR_SEND_MSG1) {
            session.resetHandshake(isInitiator = true)
        }

        val msg1 = try {
            session.handshake.writeMessage1()
        } catch (e: Exception) {
            android.util.Log.w("NoiseSessionManager", "writeMessage1 failed, resetting session for $peerNodeId", e)
            session.resetHandshake(isInitiator = true)
            session.handshake.writeMessage1()
        }

        return MeshPacket(
            type = PacketType.NOISE_HANDSHAKE,
            ttl = MeshPacket.DEFAULT_TTL,
            hopCount = 0,
            packetId = secureRandom.nextLong(),
            senderNodeId = localIdentity.nodeId,
            destNodeId = peerNodeId,
            chunkIndex = 0,
            chunkCount = 1,
            payload = msg1
        )
    }

    /**
     * Processes an incoming NOISE_HANDSHAKE packet.
     * Returns a response [MeshPacket] if the handshake state machine requires a reply (Msg 2 or Msg 3),
     * or null if the handshake has completed on this end.
     */
    @Synchronized
    fun handleIncomingHandshakePacket(packet: MeshPacket): MeshPacket? {
        if (packet.type != PacketType.NOISE_HANDSHAKE) return null

        val peerNodeId = packet.senderNodeId
        val payload = packet.payload

        val existingSession = sessions[peerNodeId]

        try {
            if (existingSession == null) {
                // We are the responder receiving Message 1
                val session = getOrCreateSession(peerNodeId, isInitiator = false)
                val msg2 = session.handshake.processMessage1AndWriteMessage2(payload)

                return MeshPacket(
                    type = PacketType.NOISE_HANDSHAKE,
                    ttl = MeshPacket.DEFAULT_TTL,
                    hopCount = 0,
                    packetId = secureRandom.nextLong(),
                    senderNodeId = localIdentity.nodeId,
                    destNodeId = peerNodeId,
                    chunkIndex = 0,
                    chunkCount = 1,
                    payload = msg2
                )
            } else if (existingSession.isHandshakeComplete) {
                // Peer may have restarted and sent a fresh Message 1 (32 bytes)
                if (payload.size == 32) {
                    existingSession.resetHandshake(isInitiator = false)
                    val msg2 = existingSession.handshake.processMessage1AndWriteMessage2(payload)
                    return MeshPacket(
                        type = PacketType.NOISE_HANDSHAKE,
                        ttl = MeshPacket.DEFAULT_TTL,
                        hopCount = 0,
                        packetId = secureRandom.nextLong(),
                        senderNodeId = localIdentity.nodeId,
                        destNodeId = peerNodeId,
                        chunkIndex = 0,
                        chunkCount = 1,
                        payload = msg2
                    )
                }
                return null
            } else if (existingSession.isInitiator && existingSession.handshake.step == NoiseHandshake.HandshakeStep.INITIATOR_WAIT_MSG2) {
                if (payload.size == 32) {
                    // Simultaneous initiation collision: both sent Message 1 upon discovery.
                    // Tie-break deterministically: node with lower ID yields and becomes responder.
                    if (localIdentity.nodeId < peerNodeId) {
                        existingSession.resetHandshake(isInitiator = false)
                        val msg2 = existingSession.handshake.processMessage1AndWriteMessage2(payload)
                        return MeshPacket(
                            type = PacketType.NOISE_HANDSHAKE,
                            ttl = MeshPacket.DEFAULT_TTL,
                            hopCount = 0,
                            packetId = secureRandom.nextLong(),
                            senderNodeId = localIdentity.nodeId,
                            destNodeId = peerNodeId,
                            chunkIndex = 0,
                            chunkCount = 1,
                            payload = msg2
                        )
                    } else {
                        // Higher ID: retain initiator role and await peer's Message 2 response
                        return null
                    }
                }

                // Normal path: initiator receiving Message 2 -> process and send Message 3
                val (msg3, splitCiphers) = existingSession.handshake.processMessage2AndWriteMessage3(payload)
                existingSession.setTransportCiphers(splitCiphers.first, splitCiphers.second)

                return MeshPacket(
                    type = PacketType.NOISE_HANDSHAKE,
                    ttl = MeshPacket.DEFAULT_TTL,
                    hopCount = 0,
                    packetId = secureRandom.nextLong(),
                    senderNodeId = localIdentity.nodeId,
                    destNodeId = peerNodeId,
                    chunkIndex = 0,
                    chunkCount = 1,
                    payload = msg3
                )
            } else if (!existingSession.isInitiator && existingSession.handshake.step == NoiseHandshake.HandshakeStep.RESPONDER_WAIT_MSG3) {
                if (payload.size == 32) {
                    // Peer restarted and re-sent Message 1
                    existingSession.resetHandshake(isInitiator = false)
                    val msg2 = existingSession.handshake.processMessage1AndWriteMessage2(payload)
                    return MeshPacket(
                        type = PacketType.NOISE_HANDSHAKE,
                        ttl = MeshPacket.DEFAULT_TTL,
                        hopCount = 0,
                        packetId = secureRandom.nextLong(),
                        senderNodeId = localIdentity.nodeId,
                        destNodeId = peerNodeId,
                        chunkIndex = 0,
                        chunkCount = 1,
                        payload = msg2
                    )
                }

                // Normal path: responder receiving Message 3 -> complete handshake
                val (sendCipher, recvCipher) = existingSession.handshake.processMessage3(payload)
                existingSession.setTransportCiphers(sendCipher, recvCipher)
                return null
            } else {
                // Out-of-sync state: if incoming payload is Message 1 (32 bytes), reset cleanly to responder
                if (payload.size == 32) {
                    existingSession.resetHandshake(isInitiator = false)
                    val msg2 = existingSession.handshake.processMessage1AndWriteMessage2(payload)
                    return MeshPacket(
                        type = PacketType.NOISE_HANDSHAKE,
                        ttl = MeshPacket.DEFAULT_TTL,
                        hopCount = 0,
                        packetId = secureRandom.nextLong(),
                        senderNodeId = localIdentity.nodeId,
                        destNodeId = peerNodeId,
                        chunkIndex = 0,
                        chunkCount = 1,
                        payload = msg2
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NoiseSessionManager", "Error processing handshake packet from $peerNodeId", e)
            existingSession?.resetHandshake(isInitiator = false)
        }

        return null
    }

    /**
     * Encrypts plaintext destined for [peerNodeId] using the active session.
     */
    fun encrypt(peerNodeId: String, plaintext: ByteArray): ByteArray {
        val session = sessions[peerNodeId]
            ?: throw IllegalStateException("No active Noise session for peer $peerNodeId")
        return session.encrypt(plaintext)
    }

    /**
     * Decrypts ciphertext received from [peerNodeId] using the active session.
     */
    fun decrypt(peerNodeId: String, ciphertext: ByteArray): ByteArray {
        val session = sessions[peerNodeId]
            ?: throw IllegalStateException("No active Noise session for peer $peerNodeId")
        return session.decrypt(ciphertext)
    }

    /**
     * Initiates a Noise_XX handshake with [peerNodeId] and returns the raw Message 1 bytes
     * (-> e) for direct transmission over GATT. Does NOT wrap in a MeshPacket.
     * Returns null if the session is already complete or a handshake is already in progress.
     */
    @Synchronized
    fun initiateHandshakeBytes(peerNodeId: String): ByteArray? {
        val session = getOrCreateSession(peerNodeId, isInitiator = true)

        if (session.isHandshakeComplete) return null
        if (session.isHandshakeInProgress) return null

        if (session.handshake.step != NoiseHandshake.HandshakeStep.INITIATOR_SEND_MSG1) {
            session.resetHandshake(isInitiator = true)
        }

        return try {
            session.handshake.writeMessage1()
        } catch (e: Exception) {
            android.util.Log.w("NoiseSessionManager", "initiateHandshakeBytes: writeMessage1 failed for $peerNodeId, resetting", e)
            session.resetHandshake(isInitiator = true)
            try { session.handshake.writeMessage1() } catch (e2: Exception) { null }
        }
    }

    /**
     * Processes an incoming raw handshake payload from [peerNodeId] and drives the
     * Noise_XX state machine for both initiator and responder sides.
     *
     * @param peerNodeId   the sender's node ID
     * @param isInitiatorSide  true if WE are the initiator (GATT client), false if responder (GATT server)
     * @param incomingBytes  the raw handshake bytes received over GATT
     * @return [org.nearby.mesh.ble.HandshakeStepResult] indicating next action
     */
    @Synchronized
    fun processHandshakeBytes(
        peerNodeId: String,
        isInitiatorSide: Boolean,
        incomingBytes: ByteArray
    ): org.nearby.mesh.ble.HandshakeStepResult {
        return try {
            if (isInitiatorSide) {
                // Initiator: we sent Msg1, now receiving Msg2 → produce Msg3 and complete
                val session = sessions[peerNodeId]
                    ?: return org.nearby.mesh.ble.HandshakeStepResult.Error("No initiator session for $peerNodeId")

                if (session.handshake.step != NoiseHandshake.HandshakeStep.INITIATOR_WAIT_MSG2) {
                    return org.nearby.mesh.ble.HandshakeStepResult.Error(
                        "Initiator not in WAIT_MSG2 state for $peerNodeId (step=${session.handshake.step})"
                    )
                }

                val (msg3, splitCiphers) = session.handshake.processMessage2AndWriteMessage3(incomingBytes)
                session.setTransportCiphers(splitCiphers.first, splitCiphers.second)
                // Initiator sends Msg3 to finalize
                org.nearby.mesh.ble.HandshakeStepResult.SendResponse(msg3)
            } else {
                // Responder: receiving either Msg1 or Msg3
                val existingSession = sessions[peerNodeId]

                if (existingSession == null ||
                    existingSession.handshake.step == NoiseHandshake.HandshakeStep.RESPONDER_WAIT_MSG1
                ) {
                    // Receiving Msg1 → create/reset session as responder and produce Msg2
                    val session = getOrCreateSession(peerNodeId, isInitiator = false)
                    if (session.handshake.step != NoiseHandshake.HandshakeStep.RESPONDER_WAIT_MSG1) {
                        session.resetHandshake(isInitiator = false)
                    }
                    val msg2 = session.handshake.processMessage1AndWriteMessage2(incomingBytes)
                    org.nearby.mesh.ble.HandshakeStepResult.SendResponse(msg2)
                } else if (existingSession.handshake.step == NoiseHandshake.HandshakeStep.RESPONDER_WAIT_MSG3) {
                    // Receiving Msg3 → complete handshake
                    val (sendCipher, recvCipher) = existingSession.handshake.processMessage3(incomingBytes)
                    existingSession.setTransportCiphers(sendCipher, recvCipher)
                    org.nearby.mesh.ble.HandshakeStepResult.Complete
                } else {
                    org.nearby.mesh.ble.HandshakeStepResult.Error(
                        "Responder in unexpected state ${existingSession.handshake.step} for $peerNodeId"
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NoiseSessionManager", "processHandshakeBytes failed for $peerNodeId", e)
            sessions[peerNodeId]?.resetHandshake(isInitiator = !isInitiatorSide)
            org.nearby.mesh.ble.HandshakeStepResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Creates and completes a fully simulated mutual Noise session for debug/single-device testing.
     */
    fun createSimulatedSession(
        peerNodeId: String,
        mockStaticPrivKey: ByteArray,
        mockStaticPubKey: ByteArray
    ): NoiseSession {
        val aliceSession = getOrCreateSession(peerNodeId, isInitiator = true)
        val mockResponder = NoiseHandshake(
            isInitiator = false,
            localStaticPrivateKey = mockStaticPrivKey,
            localStaticPublicKey = mockStaticPubKey
        )

        // Step 1: Alice writes Msg 1
        val msg1 = aliceSession.handshake.writeMessage1()
        // Step 2: Mock responder processes Msg 1 and writes Msg 2
        val msg2 = mockResponder.processMessage1AndWriteMessage2(msg1)
        // Step 3: Alice processes Msg 2 and writes Msg 3, obtaining transport ciphers
        val (msg3, aliceCiphers) = aliceSession.handshake.processMessage2AndWriteMessage3(msg2)
        aliceSession.setTransportCiphers(aliceCiphers.first, aliceCiphers.second)
        // Step 4: Mock responder completes handshake with Msg 3
        mockResponder.processMessage3(msg3)

        return aliceSession
    }
}
