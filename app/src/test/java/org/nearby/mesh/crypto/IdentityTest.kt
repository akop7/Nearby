package org.nearby.mesh.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyGenerator

class IdentityTest {

    @Test
    fun defaultDisplayName_withoutNodeId_matchesSurvivorPattern() {
        val pattern = IdentityManager.DISPLAY_NAME_PATTERN
        assertEquals("^Survivor-[0-9A-F]{4}$", pattern.pattern)

        // Generate multiple random pseudonyms to ensure consistent conformity
        repeat(100) {
            val displayName = IdentityManager.generateDefaultDisplayName()
            assertTrue(
                "Expected '$displayName' to match pattern '${pattern.pattern}'",
                pattern.matches(displayName)
            )
            assertTrue(displayName.startsWith(IdentityManager.DISPLAY_NAME_PREFIX))
            assertEquals(13, displayName.length) // "Survivor-" is 9 chars + 4 hex digits = 13 chars
        }
    }

    @Test
    fun defaultDisplayName_withNodeId_isDeterministicAndMatchesPattern() {
        val pattern = IdentityManager.DISPLAY_NAME_PATTERN

        val testNodeId = "a1b2c3d4e5f67890"
        val displayName = IdentityManager.generateDefaultDisplayName(testNodeId)

        assertEquals("Survivor-7890", displayName)
        assertTrue(pattern.matches(displayName))

        val anotherNodeId = "001122334455aabb"
        val anotherDisplayName = IdentityManager.generateDefaultDisplayName(anotherNodeId)
        assertEquals("Survivor-AABB", anotherDisplayName)
        assertTrue(pattern.matches(anotherDisplayName))
    }

    @Test
    fun deriveNodeId_isDeterministicForSamePublicKey() {
        val testPublicKey = ByteArray(32) { it.toByte() }

        val nodeId1 = IdentityManager.deriveNodeId(testPublicKey)
        val nodeId2 = IdentityManager.deriveNodeId(testPublicKey)

        assertEquals(nodeId1, nodeId2)
        assertEquals(16, nodeId1.length) // 8 bytes represented as hex = 16 hex characters
        assertTrue(IdentityManager.NODE_ID_PATTERN.matches(nodeId1))
    }

    @Test
    fun deriveNodeId_differsForDifferentPublicKeys() {
        val keyA = ByteArray(32) { 0x01 }
        val keyB = ByteArray(32) { 0x02 }

        val nodeIdA = IdentityManager.deriveNodeId(keyA)
        val nodeIdB = IdentityManager.deriveNodeId(keyB)

        assertNotEquals(nodeIdA, nodeIdB)
        assertEquals(16, nodeIdA.length)
        assertEquals(16, nodeIdB.length)
        assertTrue(IdentityManager.NODE_ID_PATTERN.matches(nodeIdA))
        assertTrue(IdentityManager.NODE_ID_PATTERN.matches(nodeIdB))
    }

    @Test
    fun deriveNodeId_matchesKnownSha256DigestPrefix() {
        // A known 32-byte input of zeroes
        val zeroKey = ByteArray(32) { 0 }
        val fullSha256 = MessageDigest.getInstance("SHA-256").digest(zeroKey)
        val expectedFirst8BytesHex = fullSha256.take(8).joinToString("") { "%02x".format(it) }

        val actualNodeId = IdentityManager.deriveNodeId(zeroKey)

        assertEquals(expectedFirst8BytesHex, actualNodeId)
        assertEquals("66687aadf862bd77", actualNodeId)
        assertEquals(16, actualNodeId.length)
    }

    @Test
    fun nodeIdentity_equalityAndHashCode_basedOnContent() {
        val pubKey1 = ByteArray(32) { 10 }
        val pubKey2 = ByteArray(32) { 10 }
        val privKey1 = ByteArray(32) { 20 }
        val privKey2 = ByteArray(32) { 20 }

        val id1 = NodeIdentity(
            nodeId = "66687aadf862bd77",
            publicKeyBytes = pubKey1,
            privateKeyBytes = privKey1,
            displayName = "Survivor-1234"
        )

        val id2 = NodeIdentity(
            nodeId = "66687aadf862bd77",
            publicKeyBytes = pubKey2,
            privateKeyBytes = privKey2,
            displayName = "Survivor-1234"
        )

        assertEquals(id1, id2)
        assertEquals(id1.hashCode(), id2.hashCode())

        // Different nodeId
        val idDifferentNode = id1.copy(nodeId = "ffffffffffffffff")
        assertNotEquals(id1, idDifferentNode)

        // Different displayName
        val idDifferentName = id1.copy(displayName = "Survivor-9999")
        assertNotEquals(id1, idDifferentName)

        // Null private key (hardware-backed scenario)
        val idHardwareBacked = id1.copy(privateKeyBytes = null)
        assertNotEquals(id1, idHardwareBacked)
    }

    @Test
    fun nodeIdentity_toString_redactsPrivateKeyMaterial() {
        val pubKey = ByteArray(32) { 1 }
        val privKey = ByteArray(32) { 2 }
        val identity = NodeIdentity(
            nodeId = "1234567890abcdef",
            publicKeyBytes = pubKey,
            privateKeyBytes = privKey,
            displayName = "Survivor-ABCD"
        )

        val stringRepresentation = identity.toString()

        assertTrue(stringRepresentation.contains("[REDACTED]"))
        assertFalse(stringRepresentation.contains(privKey.joinToString()))
        assertTrue(stringRepresentation.contains("1234567890abcdef"))
        assertTrue(stringRepresentation.contains("Survivor-ABCD"))

        val hwIdentity = identity.copy(privateKeyBytes = null)
        assertTrue(hwIdentity.toString().contains("null"))
    }

    @Test
    fun aesGcmWrapping_encryptsAndDecrypts32ByteKeyCorrectly() {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val masterKey = keyGen.generateKey()

        val secureRandom = SecureRandom()
        val originalPrivateKey = ByteArray(32).apply { secureRandom.nextBytes(this) }

        val (ciphertext, iv) = IdentityManager.wrapPrivateKeyWithKey(originalPrivateKey, masterKey)

        assertEquals(12, iv.size) // Standard GCM IV length
        assertFalse(ciphertext.contentEquals(originalPrivateKey))

        val unwrappedKey = IdentityManager.unwrapPrivateKeyWithKey(ciphertext, iv, masterKey)
        assertArrayEquals(originalPrivateKey, unwrappedKey)
    }

    @Test
    fun extractRawPublicKeyBytes_handlesRawAndX509Formats() {
        // Raw 32 bytes
        val raw32 = ByteArray(32) { it.toByte() }
        val dummyRawKey = object : java.security.PublicKey {
            override fun getAlgorithm(): String = "Ed25519"
            override fun getFormat(): String = "RAW"
            override fun getEncoded(): ByteArray = raw32
        }
        val extractedRaw = IdentityManager.extractRawPublicKeyBytes(dummyRawKey)
        assertArrayEquals(raw32, extractedRaw)

        // X.509 44-byte format (12-byte header + 32-byte key)
        val x509Header = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
        )
        val x509Encoded = x509Header + raw32
        assertEquals(44, x509Encoded.size)

        val dummyX509Key = object : java.security.PublicKey {
            override fun getAlgorithm(): String = "Ed25519"
            override fun getFormat(): String = "X.509"
            override fun getEncoded(): ByteArray = x509Encoded
        }
        val extractedFromX509 = IdentityManager.extractRawPublicKeyBytes(dummyX509Key)
        assertArrayEquals(raw32, extractedFromX509)
    }
}
