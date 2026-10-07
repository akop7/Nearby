package org.nearby.mesh.noise

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Low-level cryptographic primitives supporting the Noise Protocol framework.
 */
object NoiseCrypto {

    private val secureRandom = SecureRandom()
    private val P_CURVE25519: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
    private val A24_CURVE25519: BigInteger = BigInteger.valueOf(121665) // (486662 - 2) / 4

    /**
     * HKDF-SHA256 Extract and Expand as specified in RFC 5869 and Noise Protocol.
     */
    fun hkdf2(ck: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
        val prk = hmacSha256(ck, ikm)
        val k1 = hmacSha256(prk, byteArrayOf(0x01))
        val k2 = hmacSha256(prk, k1 + byteArrayOf(0x02))
        return Pair(k1, k2)
    }

    /**
     * HKDF-SHA256 returning 3 derived keys (used for mixKeyAndHash).
     */
    fun hkdf3(ck: ByteArray, ikm: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val prk = hmacSha256(ck, ikm)
        val k1 = hmacSha256(prk, byteArrayOf(0x01))
        val k2 = hmacSha256(prk, k1 + byteArrayOf(0x02))
        val k3 = hmacSha256(prk, k2 + byteArrayOf(0x03))
        return Triple(k1, k2, k3)
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    /**
     * AEAD encryption (AES-256-GCM) with 96-bit nonce and associated authenticated data (AAD).
     */
    fun aeadEncrypt(key: ByteArray, nonce: Long, ad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = formatNonce(nonce)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (ad.isNotEmpty()) {
            cipher.updateAAD(ad)
        }
        return cipher.doFinal(plaintext)
    }

    /**
     * AEAD decryption (AES-256-GCM) with 96-bit nonce and associated authenticated data (AAD).
     */
    fun aeadDecrypt(key: ByteArray, nonce: Long, ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = formatNonce(nonce)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (ad.isNotEmpty()) {
            cipher.updateAAD(ad)
        }
        return cipher.doFinal(ciphertext)
    }

    private fun formatNonce(nonce: Long): ByteArray {
        // Noise standard 96-bit (12-byte) nonce: 4 zero bytes + 8-byte big-endian counter
        val iv = ByteArray(12)
        val buffer = ByteBuffer.wrap(iv)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(0)
        buffer.putLong(nonce)
        return iv
    }

    /**
     * Generates a 32-byte X25519 keypair via pure RFC 7748.
     * Returns Pair of (publicKeyBytes, privateKeyBytes).
     */
    fun generateX25519KeyPair(): Pair<ByteArray, ByteArray> {
        val priv = ByteArray(32).apply { secureRandom.nextBytes(this) }
        val pub = rfc7748ScalarMultBase(priv)
        return Pair(pub, priv)
    }

    /**
     * Performs Diffie-Hellman key agreement on Curve25519 via pure RFC 7748.
     */
    fun diffieHellman(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        return rfc7748ScalarMult(privateKey, publicKey)
    }

    fun getPublicKeyFromPrivateKey(privateKey: ByteArray): ByteArray {
        return rfc7748ScalarMultBase(privateKey)
    }

    // --- RFC 7748 Pure-Kotlin Fallback ---

    private fun rfc7748ScalarMultBase(scalar: ByteArray): ByteArray {
        val basePointU = ByteArray(32).apply { this[0] = 9 }
        return rfc7748ScalarMult(scalar, basePointU)
    }

    fun rfc7748ScalarMult(scalar: ByteArray, uCoordinate: ByteArray): ByteArray {
        val clampedScalar = scalar.copyOf(32)
        clampedScalar[0] = (clampedScalar[0].toInt() and 248).toByte()
        clampedScalar[31] = (clampedScalar[31].toInt() and 127).toByte()
        clampedScalar[31] = (clampedScalar[31].toInt() or 64).toByte()

        val u = decodeLittleEndian(uCoordinate)

        var x1 = u
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = u
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val bit = (clampedScalar[t / 8].toInt() shr (t % 8)) and 1
            swap = swap xor bit
            if (swap == 1) {
                var temp = x2; x2 = x3; x3 = temp
                temp = z2; z2 = z3; z3 = temp
            }
            swap = bit

            val A = (x2.add(z2)).mod(P_CURVE25519)
            val AA = (A.multiply(A)).mod(P_CURVE25519)
            val B = (x2.subtract(z2)).mod(P_CURVE25519)
            val BB = (B.multiply(B)).mod(P_CURVE25519)
            val E = (AA.subtract(BB)).mod(P_CURVE25519)
            val C = (x3.add(z3)).mod(P_CURVE25519)
            val D = (x3.subtract(z3)).mod(P_CURVE25519)
            val DA = (D.multiply(A)).mod(P_CURVE25519)
            val CB = (C.multiply(B)).mod(P_CURVE25519)

            x3 = ((DA.add(CB)).pow(2)).mod(P_CURVE25519)
            z3 = (x1.multiply((DA.subtract(CB)).pow(2))).mod(P_CURVE25519)
            x2 = (AA.multiply(BB)).mod(P_CURVE25519)
            z2 = (E.multiply(AA.add(A24_CURVE25519.multiply(E)))).mod(P_CURVE25519)
        }

        if (swap == 1) {
            val temp = x2; x2 = x3; x3 = temp
            val tempZ = z2; z2 = z3; z3 = tempZ
        }

        val result = (x2.multiply(z2.modPow(P_CURVE25519.subtract(BigInteger.valueOf(2)), P_CURVE25519))).mod(P_CURVE25519)
        return encodeLittleEndian(result)
    }

    private fun decodeLittleEndian(b: ByteArray): BigInteger {
        val reversed = b.reversedArray()
        return BigInteger(1, reversed)
    }

    private fun encodeLittleEndian(n: BigInteger): ByteArray {
        val bytes = n.toByteArray()
        val result = ByteArray(32)
        val copyLen = minOf(bytes.size, 32)
        for (i in 0 until copyLen) {
            result[i] = bytes[bytes.size - 1 - i]
        }
        return result
    }
}
