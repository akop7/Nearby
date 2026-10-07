package org.nearby.mesh.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.nearby.mesh.domain.PeerTrustRecord
import org.nearby.mesh.domain.PeerTrustState
import org.nearby.mesh.noise.NoiseCrypto
import org.nearby.mesh.noise.NoiseHandshake
import org.nearby.mesh.noise.NoiseSessionManager
import org.nearby.mesh.protocol.PacketType
import java.security.SecureRandom

class NoiseHandshakeTest {

    private val secureRandom = SecureRandom()

    @Test
    fun x25519_diffieHellmanKeyAgreement_isSymmetric() {
        val (alicePub, alicePriv) = NoiseCrypto.generateX25519KeyPair()
        val (bobPub, bobPriv) = NoiseCrypto.generateX25519KeyPair()

        val secret1 = NoiseCrypto.diffieHellman(alicePriv, bobPub)
        val secret2 = NoiseCrypto.diffieHellman(bobPriv, alicePub)

        assertEquals(32, secret1.size)
        assertEquals(32, secret2.size)
        assertArrayEquals(secret1, secret2)
    }

    @Test
    fun fullNoiseXXHandshakeProgression_completesAndDerivesIdenticalSasCode() {
        val (alicePub, alicePriv) = NoiseCrypto.generateX25519KeyPair()
        val (bobPub, bobPriv) = NoiseCrypto.generateX25519KeyPair()

        val alice = NoiseHandshake(isInitiator = true, localStaticPrivateKey = alicePriv, localStaticPublicKey = alicePub)
        val bob = NoiseHandshake(isInitiator = false, localStaticPrivateKey = bobPriv, localStaticPublicKey = bobPub)

        assertEquals(NoiseHandshake.HandshakeStep.INITIATOR_SEND_MSG1, alice.step)
        assertEquals(NoiseHandshake.HandshakeStep.RESPONDER_WAIT_MSG1, bob.step)

        // Step 1: Alice -> Bob (e)
        val msg1 = alice.writeMessage1()
        assertEquals(NoiseHandshake.HandshakeStep.INITIATOR_WAIT_MSG2, alice.step)

        // Step 2: Bob <- Alice, Bob -> Alice (e, ee, s, es)
        val msg2 = bob.processMessage1AndWriteMessage2(msg1)
        assertEquals(NoiseHandshake.HandshakeStep.RESPONDER_WAIT_MSG3, bob.step)

        // Step 3: Alice <- Bob, Alice -> Bob (s, se)
        val (msg3, aliceCiphers) = alice.processMessage2AndWriteMessage3(msg2)
        assertEquals(NoiseHandshake.HandshakeStep.COMPLETE, alice.step)

        // Step 4: Bob <- Alice (s, se)
        val bobCiphers = bob.processMessage3(msg3)
        assertEquals(NoiseHandshake.HandshakeStep.COMPLETE, bob.step)

        // Verify mutually authenticated static public keys
        assertArrayEquals(bobPub, alice.remoteStaticPublicKey)
        assertArrayEquals(alicePub, bob.remoteStaticPublicKey)

        // Verify Short Authentication String (SAS) matching
        val aliceSas = alice.calculateSasCode()
        val bobSas = bob.calculateSasCode()
        assertEquals(6, aliceSas.length)
        assertTrue(aliceSas.matches(Regex("^[0-9]{6}$")))
        assertEquals(aliceSas, bobSas)

        // Verify symmetric AEAD transport communication
        val (aliceSend, aliceRecv) = aliceCiphers
        val (bobSend, bobRecv) = bobCiphers

        val alicePlaintext = "Emergency alert: Medical aid needed".toByteArray(Charsets.UTF_8)
        val aliceCiphertext = aliceSend.encryptWithAd(ByteArray(0), alicePlaintext)
        assertFalse(aliceCiphertext.contentEquals(alicePlaintext))

        val decryptedByBob = bobRecv.decryptWithAd(ByteArray(0), aliceCiphertext)
        assertArrayEquals(alicePlaintext, decryptedByBob)

        val bobPlaintext = "Copy that. Unit 4 en route.".toByteArray(Charsets.UTF_8)
        val bobCiphertext = bobSend.encryptWithAd(ByteArray(0), bobPlaintext)
        val decryptedByAlice = aliceRecv.decryptWithAd(ByteArray(0), bobCiphertext)
        assertArrayEquals(bobPlaintext, decryptedByAlice)
    }

    @Test
    fun forwardSecrecy_nonceProgressionAndDifferentCiphertexts() {
        val (alicePub, alicePriv) = NoiseCrypto.generateX25519KeyPair()
        val (bobPub, bobPriv) = NoiseCrypto.generateX25519KeyPair()

        val alice = NoiseHandshake(true, alicePriv, alicePub)
        val bob = NoiseHandshake(false, bobPriv, bobPub)

        val msg1 = alice.writeMessage1()
        val msg2 = bob.processMessage1AndWriteMessage2(msg1)
        val (msg3, aliceCiphers) = alice.processMessage2AndWriteMessage3(msg2)
        val bobCiphers = bob.processMessage3(msg3)

        val (aliceSend, _) = aliceCiphers
        val (_, bobRecv) = bobCiphers

        val plaintext = "Heartbeat ping".toByteArray(Charsets.UTF_8)

        val cipher1 = aliceSend.encryptWithAd(ByteArray(0), plaintext)
        val cipher2 = aliceSend.encryptWithAd(ByteArray(0), plaintext)

        // Nonce increment produces distinct ciphertext for identical plaintext
        assertFalse(cipher1.contentEquals(cipher2))

        // Bob decrypts both in exact sequence
        val decrypted1 = bobRecv.decryptWithAd(ByteArray(0), cipher1)
        val decrypted2 = bobRecv.decryptWithAd(ByteArray(0), cipher2)

        assertArrayEquals(plaintext, decrypted1)
        assertArrayEquals(plaintext, decrypted2)
    }

    @Test
    fun tamperedHandshakeMessage_failsAuthentication() {
        val (alicePub, alicePriv) = NoiseCrypto.generateX25519KeyPair()
        val (bobPub, bobPriv) = NoiseCrypto.generateX25519KeyPair()

        val alice = NoiseHandshake(true, alicePriv, alicePub)
        val bob = NoiseHandshake(false, bobPriv, bobPub)

        val msg1 = alice.writeMessage1()
        val msg2 = bob.processMessage1AndWriteMessage2(msg1)

        // Tamper with ciphertext tag in message 2
        msg2[msg2.size - 1] = (msg2[msg2.size - 1].toInt() xor 0xFF).toByte()

        try {
            alice.processMessage2AndWriteMessage3(msg2)
            fail("Expected AEAD authentication failure on tampered handshake message")
        } catch (e: Exception) {
            // Success: Tampered message rejected per Rules.md §5
            assertNotEquals(NoiseHandshake.HandshakeStep.COMPLETE, alice.step)
        }
    }

    @Test
    fun noiseSessionManager_orchestrationBetweenTwoPeers() {
        val (alicePub, alicePriv) = NoiseCrypto.generateX25519KeyPair()
        val (bobPub, bobPriv) = NoiseCrypto.generateX25519KeyPair()

        val aliceIdentity = NodeIdentity(
            nodeId = "aaaaaaaaaaaaaaaa",
            publicKeyBytes = alicePub,
            privateKeyBytes = alicePriv,
            displayName = "Survivor-AAAA"
        )
        val bobIdentity = NodeIdentity(
            nodeId = "bbbbbbbbbbbbbbbb",
            publicKeyBytes = bobPub,
            privateKeyBytes = bobPriv,
            displayName = "Survivor-BBBB"
        )

        val aliceManager = NoiseSessionManager(aliceIdentity)
        val bobManager = NoiseSessionManager(bobIdentity)

        // Alice initiates handshake
        val packet1 = aliceManager.initiateHandshake(bobIdentity.nodeId)
        assertNotNull(packet1)
        assertEquals(PacketType.NOISE_HANDSHAKE, packet1!!.type)
        assertEquals(bobIdentity.nodeId, packet1.destNodeId)

        // Bob receives packet 1 -> produces packet 2
        val packet2 = bobManager.handleIncomingHandshakePacket(packet1)
        assertNotNull(packet2)
        assertEquals(aliceIdentity.nodeId, packet2!!.destNodeId)

        // Alice receives packet 2 -> produces packet 3
        val packet3 = aliceManager.handleIncomingHandshakePacket(packet2)
        assertNotNull(packet3)

        // Bob receives packet 3 -> completes handshake
        val packet4 = bobManager.handleIncomingHandshakePacket(packet3!!)
        assertEquals(null, packet4) // No further message needed

        assertTrue(aliceManager.isSessionEstablished(bobIdentity.nodeId))
        assertTrue(bobManager.isSessionEstablished(aliceIdentity.nodeId))

        val aliceSas = aliceManager.getSasCode(bobIdentity.nodeId)
        val bobSas = bobManager.getSasCode(aliceIdentity.nodeId)
        assertNotNull(aliceSas)
        assertEquals(aliceSas, bobSas)

        // Verify bidirectional session encrypt / decrypt
        val msgToBob = "Mesh test message from Alice".toByteArray()
        val encForBob = aliceManager.encrypt(bobIdentity.nodeId, msgToBob)
        val decByBob = bobManager.decrypt(aliceIdentity.nodeId, encForBob)
        assertArrayEquals(msgToBob, decByBob)

        val msgToAlice = "Acknowledged by Bob".toByteArray()
        val encForAlice = bobManager.encrypt(aliceIdentity.nodeId, msgToAlice)
        val decByAlice = aliceManager.decrypt(bobIdentity.nodeId, encForAlice)
        assertArrayEquals(msgToAlice, decByAlice)
    }

    @Test
    fun peerTrustRecord_equalityAndBehavior() {
        val key = ByteArray(32) { 7 }
        val record1 = PeerTrustRecord(
            nodeId = "1122334455667788",
            publicKeyBytes = key,
            trustState = PeerTrustState.VERIFIED,
            verifiedAtMs = 123456L,
            customLabel = "Team Lead"
        )
        val record2 = PeerTrustRecord(
            nodeId = "1122334455667788",
            publicKeyBytes = key.copyOf(),
            trustState = PeerTrustState.VERIFIED,
            verifiedAtMs = 123456L,
            customLabel = "Team Lead"
        )

        assertEquals(record1, record2)
        assertEquals(record1.hashCode(), record2.hashCode())
    }

    @Test
    fun noiseSessionManager_createSimulatedSession_establishesCompleteSessionWithSas() {
        val aliceIdentity = NodeIdentity(
            nodeId = "1111111111111111",
            publicKeyBytes = ByteArray(32) { 1 },
            privateKeyBytes = ByteArray(32) { 2 },
            displayName = "Alice"
        )
        val manager = NoiseSessionManager(aliceIdentity)
        val (mockPub, mockPriv) = NoiseCrypto.generateX25519KeyPair()
        val mockNodeId = "9999999999999999"

        val session = manager.createSimulatedSession(mockNodeId, mockPriv, mockPub)
        assertTrue(session.isHandshakeComplete)
        assertNotNull(session.sasCode)
        assertEquals(6, session.sasCode?.length)
        assertTrue(manager.isSessionEstablished(mockNodeId))

        val plainText = "Test encrypted simulation".toByteArray()
        val encrypted = manager.encrypt(mockNodeId, plainText)
        assertFalse(encrypted.contentEquals(plainText))
    }
}
