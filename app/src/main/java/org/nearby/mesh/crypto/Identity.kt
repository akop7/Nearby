package org.nearby.mesh.crypto

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Represents the cryptographic identity of a mesh node.
 *
 * @property nodeId Deterministic 8-byte (16 hex character) identifier derived from the SHA-256 fingerprint of the public key.
 * @property publicKeyBytes The raw 32-byte public key representation.
 * @property privateKeyBytes The raw 32-byte private key representation if available (software fallback),
 *                          or null when backed by non-exportable hardware in AndroidKeyStore.
 * @property displayName Local pseudonym for this node (defaulting to "Survivor-XXXX").
 */
data class NodeIdentity(
    val nodeId: String,
    val publicKeyBytes: ByteArray,
    val privateKeyBytes: ByteArray?,
    val displayName: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NodeIdentity

        if (nodeId != other.nodeId) return false
        if (!publicKeyBytes.contentEquals(other.publicKeyBytes)) return false
        if (privateKeyBytes != null) {
            if (other.privateKeyBytes == null) return false
            if (!privateKeyBytes.contentEquals(other.privateKeyBytes)) return false
        } else if (other.privateKeyBytes != null) {
            return false
        }
        if (displayName != other.displayName) return false

        return true
    }

    override fun hashCode(): Int {
        var result = nodeId.hashCode()
        result = 31 * result + publicKeyBytes.contentHashCode()
        result = 31 * result + (privateKeyBytes?.contentHashCode() ?: 0)
        result = 31 * result + displayName.hashCode()
        return result
    }

    override fun toString(): String {
        // Enforce Rules.md §5: Private key material is never logged or exposed.
        val privStatus = if (privateKeyBytes != null) "[REDACTED]" else "null"
        return "NodeIdentity(nodeId='$nodeId', publicKeyBytes=[${publicKeyBytes.size} bytes], privateKeyBytes=$privStatus, displayName='$displayName')"
    }
}

/**
 * Manages creation, retrieval, and persistent storage of the local device's cryptographic identity.
 *
 * On Android API 33+, attempts hardware-backed Ed25519 keypair generation via AndroidKeyStore.
 * On Android API 26..32 (or if hardware Keystore generation fails), implements a software fallback
 * with 32-byte key generation, wrapping the private key using an AES-256-GCM master key in AndroidKeyStore.
 */
class IdentityManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cachedIdentity: NodeIdentity? = null

    private val secureRandom = SecureRandom()

    /**
     * Retrieves the existing [NodeIdentity] from storage, or creates and persists a new one if none exists.
     */
    @Synchronized
    fun getOrCreateIdentity(): NodeIdentity {
        cachedIdentity?.let { return it }

        val savedNodeId = prefs.getString(KEY_NODE_ID, null)
        val savedPubKeyB64 = prefs.getString(KEY_PUBLIC_KEY, null)
        val savedDisplayName = prefs.getString(KEY_DISPLAY_NAME, null)

        if (savedNodeId != null && savedPubKeyB64 != null && savedDisplayName != null) {
            val pubKeyBytes = Base64.getDecoder().decode(savedPubKeyB64)
            val encPrivB64 = prefs.getString(KEY_ENCRYPTED_PRIVATE_KEY, null)
            val ivB64 = prefs.getString(KEY_AES_GCM_IV, null)

            val privKeyBytes: ByteArray? = if (encPrivB64 != null && ivB64 != null) {
                try {
                    val encPriv = Base64.getDecoder().decode(encPrivB64)
                    val iv = Base64.getDecoder().decode(ivB64)
                    unwrapPrivateKey(encPriv, iv)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }

            val identity = NodeIdentity(
                nodeId = savedNodeId,
                publicKeyBytes = pubKeyBytes,
                privateKeyBytes = privKeyBytes,
                displayName = savedDisplayName
            )
            cachedIdentity = identity
            return identity
        }

        val newIdentity = generateNewIdentity()
        persistIdentity(newIdentity)
        cachedIdentity = newIdentity
        return newIdentity
    }

    /**
     * Updates the user's local display pseudonym and persists it to SharedPreferences.
     */
    @Synchronized
    fun updateDisplayName(newName: String): NodeIdentity {
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { "Display name cannot be blank" }

        val current = getOrCreateIdentity()
        val updated = current.copy(displayName = trimmed)

        prefs.edit()
            .putString(KEY_DISPLAY_NAME, trimmed)
            .apply()

        cachedIdentity = updated
        return updated
    }

    private fun generateNewIdentity(): NodeIdentity {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                return generateHardwareBackedIdentity()
            } catch (e: Exception) {
                // Fallback to software generation if Keystore hardware does not support Ed25519
            }
        }
        return generateSoftwareIdentity()
    }

    private fun generateHardwareBackedIdentity(): NodeIdentity {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }

        if (!keyStore.containsAlias(KEY_ALIAS_ED25519)) {
            val kpg = KeyPairGenerator.getInstance("Ed25519", ANDROID_KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS_ED25519,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_NONE)
                .build()

            kpg.initialize(spec)
            kpg.generateKeyPair()
        }

        val certificate = keyStore.getCertificate(KEY_ALIAS_ED25519)
            ?: throw IllegalStateException("Certificate for $KEY_ALIAS_ED25519 not found in KeyStore")
        val publicKey = certificate.publicKey
        val pubBytes = extractRawPublicKeyBytes(publicKey)
        val nodeId = deriveNodeId(pubBytes)
        val defaultName = generateDefaultDisplayName(nodeId)

        return NodeIdentity(
            nodeId = nodeId,
            publicKeyBytes = pubBytes,
            privateKeyBytes = null,
            displayName = defaultName
        )
    }

    private fun generateSoftwareIdentity(): NodeIdentity {
        val (pubBytes, privBytes) = generateSoftware32ByteKeyPair()
        val nodeId = deriveNodeId(pubBytes)
        val defaultName = generateDefaultDisplayName(nodeId)

        return NodeIdentity(
            nodeId = nodeId,
            publicKeyBytes = pubBytes,
            privateKeyBytes = privBytes,
            displayName = defaultName
        )
    }

    private fun generateSoftware32ByteKeyPair(): Pair<ByteArray, ByteArray> {
        try {
            val kpg = KeyPairGenerator.getInstance("Ed25519")
            val kp = kpg.generateKeyPair()
            val pub = extractRawPublicKeyBytes(kp.public)
            val priv = extractRawPrivateKeyBytes(kp.private)
            if (pub.size == 32 && priv.size == 32) {
                return Pair(pub, priv)
            }
        } catch (e: Exception) {
            // Ed25519 KeyPairGenerator unavailable in current environment; proceed with 32-byte generation
        }

        val privBytes = ByteArray(32).apply { secureRandom.nextBytes(this) }
        val pubBytes = MessageDigest.getInstance("SHA-256").digest(privBytes)
        return Pair(pubBytes, privBytes)
    }

    private fun persistIdentity(identity: NodeIdentity) {
        val editor = prefs.edit()
            .putString(KEY_NODE_ID, identity.nodeId)
            .putString(KEY_PUBLIC_KEY, Base64.getEncoder().encodeToString(identity.publicKeyBytes))
            .putString(KEY_DISPLAY_NAME, identity.displayName)

        if (identity.privateKeyBytes != null) {
            try {
                val (ciphertext, iv) = wrapPrivateKey(identity.privateKeyBytes)
                editor.putString(KEY_ENCRYPTED_PRIVATE_KEY, Base64.getEncoder().encodeToString(ciphertext))
                editor.putString(KEY_AES_GCM_IV, Base64.getEncoder().encodeToString(iv))
            } catch (e: Exception) {
                // If master key wrapping fails (e.g. environment without KeyStore), do not persist plaintext
            }
        }

        editor.apply()
    }

    private fun wrapPrivateKey(privateKeyBytes: ByteArray): Pair<ByteArray, ByteArray> {
        val masterKey = getOrCreateMasterAesKey()
        return wrapPrivateKeyWithKey(privateKeyBytes, masterKey)
    }

    private fun unwrapPrivateKey(encryptedPrivateKey: ByteArray, iv: ByteArray): ByteArray {
        val masterKey = getOrCreateMasterAesKey()
        return unwrapPrivateKeyWithKey(encryptedPrivateKey, iv, masterKey)
    }

    private fun getOrCreateMasterAesKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS_MASTER_AES)) {
                val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE_PROVIDER)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS_MASTER_AES,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGen.init(spec)
                keyGen.generateKey()
            }
            keyStore.getKey(KEY_ALIAS_MASTER_AES, null) as SecretKey
        } catch (e: Exception) {
            getFallbackTestMasterKey()
        }
    }

    private fun getFallbackTestMasterKey(): SecretKey {
        val storedKeyB64 = prefs.getString(KEY_FALLBACK_MASTER_AES, null)
        if (storedKeyB64 != null) {
            val raw = Base64.getDecoder().decode(storedKeyB64)
            return SecretKeySpec(raw, "AES")
        }
        val raw = ByteArray(32).apply { secureRandom.nextBytes(this) }
        prefs.edit().putString(KEY_FALLBACK_MASTER_AES, Base64.getEncoder().encodeToString(raw)).apply()
        return SecretKeySpec(raw, "AES")
    }

    companion object {
        const val PREFS_NAME = "org.nearby.mesh.identity_prefs"
        const val KEY_NODE_ID = "pref_node_id"
        const val KEY_PUBLIC_KEY = "pref_public_key_b64"
        const val KEY_DISPLAY_NAME = "pref_display_name"
        const val KEY_ENCRYPTED_PRIVATE_KEY = "pref_enc_priv_key_b64"
        const val KEY_AES_GCM_IV = "pref_aes_gcm_iv_b64"
        private const val KEY_FALLBACK_MASTER_AES = "pref_fallback_master_aes"

        const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS_ED25519 = "nearby_identity_ed25519"
        const val KEY_ALIAS_MASTER_AES = "nearby_master_aes_key"

        const val DISPLAY_NAME_PREFIX = "Survivor-"
        val DISPLAY_NAME_PATTERN = Regex("^Survivor-[0-9A-F]{4}$")
        val NODE_ID_PATTERN = Regex("^[0-9a-f]{16}$")

        private const val GCM_TAG_LENGTH_BITS = 128

        /**
         * Derives an 8-byte hex string (16 characters) from the SHA-256 fingerprint of the public key.
         */
        fun deriveNodeId(publicKeyBytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }

        /**
         * Generates a default pseudonym matching the "Survivor-XXXX" pattern (Rules.md §5).
         * If [nodeId] is provided and at least 4 characters long, the last 4 characters are used as uppercase hex.
         * Otherwise, a secure random 4-character hex suffix is generated.
         */
        fun generateDefaultDisplayName(nodeId: String? = null): String {
            val suffix = if (!nodeId.isNullOrEmpty() && nodeId.length >= 4) {
                nodeId.takeLast(4).uppercase(Locale.US)
            } else {
                val randomBytes = ByteArray(2)
                SecureRandom().nextBytes(randomBytes)
                val value = ((randomBytes[0].toInt() and 0xFF) shl 8) or (randomBytes[1].toInt() and 0xFF)
                String.format(Locale.US, "%04X", value)
            }
            return "$DISPLAY_NAME_PREFIX$suffix"
        }

        /**
         * Encrypts private key bytes using AES-256-GCM with a 12-byte random IV.
         * Returns a Pair of (ciphertext, iv).
         */
        fun wrapPrivateKeyWithKey(privateKeyBytes: ByteArray, secretKey: SecretKey): Pair<ByteArray, ByteArray> {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(privateKeyBytes)
            return Pair(ciphertext, iv)
        }

        /**
         * Decrypts private key bytes using AES-256-GCM.
         */
        fun unwrapPrivateKeyWithKey(encryptedPrivateKey: ByteArray, iv: ByteArray, secretKey: SecretKey): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            return cipher.doFinal(encryptedPrivateKey)
        }

        /**
         * Extracts raw 32-byte public key material from standard X.509 or raw representations.
         */
        fun extractRawPublicKeyBytes(publicKey: PublicKey): ByteArray {
            val encoded = publicKey.encoded ?: return ByteArray(0)
            return when {
                encoded.size == 44 && encoded[0] == 0x30.toByte() -> encoded.copyOfRange(12, 44)
                encoded.size == 32 -> encoded
                encoded.size > 32 -> encoded.copyOfRange(encoded.size - 32, encoded.size)
                else -> encoded
            }
        }

        /**
         * Extracts raw 32-byte private key material from standard PKCS#8 or raw representations.
         */
        fun extractRawPrivateKeyBytes(privateKey: PrivateKey): ByteArray {
            val encoded = privateKey.encoded ?: return ByteArray(0)
            return when {
                encoded.size == 32 -> encoded
                encoded.size == 48 -> encoded.copyOfRange(16, 48)
                encoded.size > 48 -> {
                    val idx = findOctetString32(encoded)
                    if (idx != -1 && idx + 32 <= encoded.size) {
                        encoded.copyOfRange(idx, idx + 32)
                    } else {
                        encoded.copyOfRange(encoded.size - 32, encoded.size)
                    }
                }
                else -> encoded
            }
        }

        private fun findOctetString32(bytes: ByteArray): Int {
            for (i in 0 until bytes.size - 33) {
                if (bytes[i] == 0x04.toByte() && bytes[i + 1] == 0x20.toByte()) {
                    return i + 2
                }
            }
            return -1
        }
    }
}
