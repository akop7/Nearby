package org.nearby.mesh.noise

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encapsulates an AEAD cipher state with auto-incrementing 64-bit nonce.
 */
class CipherState(var key: ByteArray? = null) {
    var nonce: Long = 0L

    val hasKey: Boolean
        get() = key != null

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val currentKey = key ?: return plaintext
        val ciphertext = NoiseCrypto.aeadEncrypt(currentKey, nonce, ad, plaintext)
        nonce++
        return ciphertext
    }

    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val currentKey = key ?: return ciphertext
        val plaintext = NoiseCrypto.aeadDecrypt(currentKey, nonce, ad, ciphertext)
        nonce++
        return plaintext
    }
}

/**
 * Manages chaining key and handshake hash progression during a Noise handshake.
 */
class SymmetricState(protocolName: String) {
    var ck: ByteArray = ByteArray(32)
    var h: ByteArray = ByteArray(32)
    val cipherState: CipherState = CipherState()

    init {
        val nameBytes = protocolName.toByteArray(Charsets.UTF_8)
        if (nameBytes.size <= 32) {
            System.arraycopy(nameBytes, 0, h, 0, nameBytes.size)
        } else {
            h = NoiseCrypto.sha256(nameBytes)
        }
        System.arraycopy(h, 0, ck, 0, 32)
    }

    fun mixKey(ikm: ByteArray) {
        val (nextCk, tempK) = NoiseCrypto.hkdf2(ck, ikm)
        ck = nextCk
        cipherState.key = tempK
        cipherState.nonce = 0L
    }

    fun mixHash(data: ByteArray) {
        val combined = h + data
        h = NoiseCrypto.sha256(combined)
    }

    fun mixKeyAndHash(ikm: ByteArray) {
        val (nextCk, tempH, tempK) = NoiseCrypto.hkdf3(ck, ikm)
        ck = nextCk
        mixHash(tempH)
        cipherState.key = tempK
        cipherState.nonce = 0L
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = cipherState.encryptWithAd(h, plaintext)
        mixHash(ciphertext)
        return ciphertext
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = cipherState.decryptWithAd(h, ciphertext)
        mixHash(ciphertext)
        return plaintext
    }

    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = NoiseCrypto.hkdf2(ck, ByteArray(0))
        val c1 = CipherState(k1)
        val c2 = CipherState(k2)
        return Pair(c1, c2)
    }
}

/**
 * Implements the Noise_XX handshake pattern (-> e, <- e, ee, s, es, -> s, se)
 * providing mutual authentication and forward secrecy.
 */
class NoiseHandshake(
    val isInitiator: Boolean,
    val localStaticPrivateKey: ByteArray,
    val localStaticPublicKey: ByteArray
) {
    private val symmetricState = SymmetricState("Noise_XX_25519_AESGCM_SHA256")

    private var localEphemeralPrivateKey: ByteArray? = null
    private var localEphemeralPublicKey: ByteArray? = null
    private var remoteEphemeralPublicKey: ByteArray? = null

    var remoteStaticPublicKey: ByteArray? = null
        private set

    enum class HandshakeStep {
        INITIATOR_SEND_MSG1,
        RESPONDER_WAIT_MSG1,
        INITIATOR_WAIT_MSG2,
        RESPONDER_WAIT_MSG3,
        COMPLETE,
        FAILED
    }

    var step: HandshakeStep = if (isInitiator) {
        HandshakeStep.INITIATOR_SEND_MSG1
    } else {
        HandshakeStep.RESPONDER_WAIT_MSG1
    }
        private set

    init {
        // Mix prologue if any (empty by default)
        symmetricState.mixHash(ByteArray(0))
    }

    /**
     * Initiator generates Message 1: -> e
     */
    fun writeMessage1(payload: ByteArray = ByteArray(0)): ByteArray {
        check(isInitiator && step == HandshakeStep.INITIATOR_SEND_MSG1) { "Invalid state for writeMessage1" }

        val (ePub, ePriv) = NoiseCrypto.generateX25519KeyPair()
        localEphemeralPublicKey = ePub
        localEphemeralPrivateKey = ePriv

        symmetricState.mixHash(ePub)
        val cipherPayload = symmetricState.encryptAndHash(payload)

        step = HandshakeStep.INITIATOR_WAIT_MSG2
        return ePub + cipherPayload
    }

    /**
     * Responder reads Message 1 (-> e) and writes Message 2: <- e, ee, s, es
     */
    fun processMessage1AndWriteMessage2(msg1: ByteArray, payload: ByteArray = ByteArray(0)): ByteArray {
        check(!isInitiator && step == HandshakeStep.RESPONDER_WAIT_MSG1) { "Invalid state for processMessage1" }
        require(msg1.size >= 32) { "Message 1 too short" }

        // Parse remote e
        val re = msg1.copyOfRange(0, 32)
        remoteEphemeralPublicKey = re
        symmetricState.mixHash(re)

        val cipherPayload = msg1.copyOfRange(32, msg1.size)
        symmetricState.decryptAndHash(cipherPayload)

        // Generate local ephemeral e
        val (ePub, ePriv) = NoiseCrypto.generateX25519KeyPair()
        localEphemeralPublicKey = ePub
        localEphemeralPrivateKey = ePriv

        // Write message 2: e, ee, s, es
        symmetricState.mixHash(ePub)
        val ee = NoiseCrypto.diffieHellman(ePriv, re)
        symmetricState.mixKey(ee)

        val encryptedS = symmetricState.encryptAndHash(localStaticPublicKey)
        val es = NoiseCrypto.diffieHellman(localStaticPrivateKey, re)
        symmetricState.mixKey(es)

        val finalPayload = symmetricState.encryptAndHash(payload)

        step = HandshakeStep.RESPONDER_WAIT_MSG3
        return ePub + encryptedS + finalPayload
    }

    /**
     * Initiator processes Message 2 (<- e, ee, s, es) and writes Message 3: -> s, se
     * Completes handshake on initiator side.
     */
    fun processMessage2AndWriteMessage3(msg2: ByteArray, payload: ByteArray = ByteArray(0)): Pair<ByteArray, Pair<CipherState, CipherState>> {
        check(isInitiator && step == HandshakeStep.INITIATOR_WAIT_MSG2) { "Invalid state for processMessage2" }
        require(msg2.size >= 32 + 48) { "Message 2 too short (needs e + encrypted s)" }

        val ePriv = localEphemeralPrivateKey ?: throw IllegalStateException("Local ephemeral key missing")

        // Parse remote e
        val re = msg2.copyOfRange(0, 32)
        remoteEphemeralPublicKey = re
        symmetricState.mixHash(re)

        val ee = NoiseCrypto.diffieHellman(ePriv, re)
        symmetricState.mixKey(ee)

        // Decrypt remote static s (32 bytes + 16 bytes tag = 48 bytes)
        val encS = msg2.copyOfRange(32, 32 + 48)
        val rs = symmetricState.decryptAndHash(encS)
        remoteStaticPublicKey = rs

        val se = NoiseCrypto.diffieHellman(ePriv, rs)
        symmetricState.mixKey(se)

        val cipherPayload = msg2.copyOfRange(32 + 48, msg2.size)
        symmetricState.decryptAndHash(cipherPayload)

        // Write message 3: s, se
        val encryptedLocalS = symmetricState.encryptAndHash(localStaticPublicKey)
        val es = NoiseCrypto.diffieHellman(localStaticPrivateKey, re)
        symmetricState.mixKey(es)

        val finalPayload = symmetricState.encryptAndHash(payload)
        val msg3 = encryptedLocalS + finalPayload

        val splitCiphers = symmetricState.split()
        // Initiator: c1 is send, c2 is receive
        step = HandshakeStep.COMPLETE
        destroyEphemeralKeys()

        return Pair(msg3, splitCiphers)
    }

    /**
     * Responder processes Message 3 (-> s, se)
     * Completes handshake on responder side.
     */
    fun processMessage3(msg3: ByteArray): Pair<CipherState, CipherState> {
        check(!isInitiator && step == HandshakeStep.RESPONDER_WAIT_MSG3) { "Invalid state for processMessage3" }
        require(msg3.size >= 48) { "Message 3 too short" }

        val ePriv = localEphemeralPrivateKey ?: throw IllegalStateException("Local ephemeral key missing")

        // Decrypt remote static key (32 bytes + 16 bytes tag = 48 bytes)
        val encS = msg3.copyOfRange(0, 48)
        val rs = symmetricState.decryptAndHash(encS)
        remoteStaticPublicKey = rs

        val se = NoiseCrypto.diffieHellman(ePriv, rs)
        symmetricState.mixKey(se)

        val cipherPayload = msg3.copyOfRange(48, msg3.size)
        symmetricState.decryptAndHash(cipherPayload)

        val splitCiphers = symmetricState.split()
        // Responder: c1 is receive, c2 is send
        step = HandshakeStep.COMPLETE
        destroyEphemeralKeys()

        return Pair(splitCiphers.second, splitCiphers.first)
    }

    /**
     * Derives a deterministic 6-digit SAS verification code from the handshake hash.
     */
    fun calculateSasCode(): String {
        val h = symmetricState.h
        val buffer = ByteBuffer.wrap(h)
        buffer.order(ByteOrder.BIG_ENDIAN)
        val value = Math.abs(buffer.getInt()) % 1_000_000
        return String.format(java.util.Locale.US, "%06d", value)
    }

    private fun destroyEphemeralKeys() {
        localEphemeralPrivateKey?.fill(0)
        localEphemeralPrivateKey = null
    }
}

/**
 * Represents an established or in-progress cryptographic session with a specific peer.
 */
class NoiseSession(
    val peerNodeId: String,
    val isInitiator: Boolean,
    private val localStaticPrivateKey: ByteArray,
    private val localStaticPublicKey: ByteArray
) {
    var handshake: NoiseHandshake = NoiseHandshake(isInitiator, localStaticPrivateKey, localStaticPublicKey)
        private set

    private var sendCipher: CipherState? = null
    private var recvCipher: CipherState? = null

    val isHandshakeComplete: Boolean
        get() = handshake.step == NoiseHandshake.HandshakeStep.COMPLETE

    val isHandshakeInProgress: Boolean
        get() = !isHandshakeComplete &&
                handshake.step != NoiseHandshake.HandshakeStep.INITIATOR_SEND_MSG1 &&
                handshake.step != NoiseHandshake.HandshakeStep.FAILED

    val peerStaticPublicKey: ByteArray?
        get() = handshake.remoteStaticPublicKey

    val sasCode: String?
        get() = if (isHandshakeComplete) handshake.calculateSasCode() else null

    fun resetHandshake(isInitiator: Boolean) {
        handshake = NoiseHandshake(isInitiator, localStaticPrivateKey, localStaticPublicKey)
        sendCipher = null
        recvCipher = null
    }

    fun setTransportCiphers(send: CipherState, recv: CipherState) {
        this.sendCipher = send
        this.recvCipher = recv
    }

    @Synchronized
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = sendCipher ?: throw IllegalStateException("Handshake not yet complete for peer $peerNodeId")
        return cipher.encryptWithAd(ByteArray(0), plaintext)
    }

    @Synchronized
    fun decrypt(ciphertext: ByteArray): ByteArray {
        val cipher = recvCipher ?: throw IllegalStateException("Handshake not yet complete for peer $peerNodeId")
        return cipher.decryptWithAd(ByteArray(0), ciphertext)
    }
}
