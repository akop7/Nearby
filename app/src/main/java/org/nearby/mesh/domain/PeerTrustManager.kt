package org.nearby.mesh.domain

import android.content.Context
import android.content.SharedPreferences
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Trust states for mesh peers (PRD §5.1, Architecture §3.5).
 */
enum class PeerTrustState {
    /** Default state prior to completing handshake. */
    UNVERIFIED,
    /** Handshake completed; opportunistic encrypted session prior to out-of-band verification. */
    ENCRYPTED,
    /** Out-of-band confirmed peer identity via 6-digit SAS code or QR scan. */
    VERIFIED,
    /** Public key mismatch with previously verified key or explicitly untrusted. */
    COMPROMISED
}

/**
 * Persistent trust record for a known mesh peer.
 */
data class PeerTrustRecord(
    val nodeId: String,
    val publicKeyBytes: ByteArray,
    val trustState: PeerTrustState,
    val verifiedAtMs: Long? = null,
    val customLabel: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PeerTrustRecord

        if (nodeId != other.nodeId) return false
        if (!publicKeyBytes.contentEquals(other.publicKeyBytes)) return false
        if (trustState != other.trustState) return false
        if (verifiedAtMs != other.verifiedAtMs) return false
        if (customLabel != other.customLabel) return false

        return true
    }

    override fun hashCode(): Int {
        var result = nodeId.hashCode()
        result = 31 * result + publicKeyBytes.contentHashCode()
        result = 31 * result + trustState.hashCode()
        result = 31 * result + (verifiedAtMs?.hashCode() ?: 0)
        result = 31 * result + (customLabel?.hashCode() ?: 0)
        return result
    }
}

/**
 * Manages peer verification states and detects key changes / MITM threats.
 */
class PeerTrustManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val cache = ConcurrentHashMap<String, PeerTrustRecord>()

    init {
        loadAllRecords()
    }

    /**
     * Evaluates the current trust state for a peer.
     * If the peer was previously verified with a different public key, flags as COMPROMISED.
     */
    @Synchronized
    fun evaluateTrust(nodeId: String, currentPublicKey: ByteArray?): PeerTrustState {
        val record = cache[nodeId] ?: return PeerTrustState.UNVERIFIED

        if (record.trustState == PeerTrustState.COMPROMISED) {
            return PeerTrustState.COMPROMISED
        }

        if (record.trustState == PeerTrustState.VERIFIED) {
            if (currentPublicKey != null && !record.publicKeyBytes.contentEquals(currentPublicKey)) {
                // Key substitution detected -> Transition to COMPROMISED
                markCompromised(nodeId)
                return PeerTrustState.COMPROMISED
            }
            return PeerTrustState.VERIFIED
        }

        return record.trustState
    }

    @Synchronized
    fun markVerified(nodeId: String, publicKey: ByteArray, customLabel: String? = null) {
        val now = System.currentTimeMillis()
        val record = PeerTrustRecord(
            nodeId = nodeId,
            publicKeyBytes = publicKey,
            trustState = PeerTrustState.VERIFIED,
            verifiedAtMs = now,
            customLabel = customLabel
        )
        cache[nodeId] = record
        persistRecord(record)
    }

    @Synchronized
    fun markCompromised(nodeId: String) {
        val existing = cache[nodeId]
        val record = existing?.copy(trustState = PeerTrustState.COMPROMISED)
            ?: PeerTrustRecord(
                nodeId = nodeId,
                publicKeyBytes = ByteArray(0),
                trustState = PeerTrustState.COMPROMISED
            )
        cache[nodeId] = record
        persistRecord(record)
    }

    @Synchronized
    fun resetTrust(nodeId: String) {
        cache.remove(nodeId)
        prefs.edit()
            .remove(KEY_PREFIX_STATE + nodeId)
            .remove(KEY_PREFIX_PUBKEY + nodeId)
            .remove(KEY_PREFIX_TIME + nodeId)
            .remove(KEY_PREFIX_LABEL + nodeId)
            .apply()
    }

    fun getRecord(nodeId: String): PeerTrustRecord? = cache[nodeId]

    fun getAllRecords(): List<PeerTrustRecord> = cache.values.toList()

    private fun loadAllRecords() {
        val allEntries = prefs.all
        for ((key, _) in allEntries) {
            if (key.startsWith(KEY_PREFIX_STATE)) {
                val nodeId = key.removePrefix(KEY_PREFIX_STATE)
                val stateStr = prefs.getString(KEY_PREFIX_STATE + nodeId, null)
                val pubKeyB64 = prefs.getString(KEY_PREFIX_PUBKEY + nodeId, null)
                val time = if (prefs.contains(KEY_PREFIX_TIME + nodeId)) prefs.getLong(KEY_PREFIX_TIME + nodeId, 0L) else null
                val label = prefs.getString(KEY_PREFIX_LABEL + nodeId, null)

                if (stateStr != null) {
                    val state = try {
                        PeerTrustState.valueOf(stateStr)
                    } catch (e: Exception) {
                        PeerTrustState.UNVERIFIED
                    }
                    val pubBytes = if (pubKeyB64 != null) Base64.getDecoder().decode(pubKeyB64) else ByteArray(0)
                    cache[nodeId] = PeerTrustRecord(nodeId, pubBytes, state, time, label)
                }
            }
        }
    }

    private fun persistRecord(record: PeerTrustRecord) {
        prefs.edit()
            .putString(KEY_PREFIX_STATE + record.nodeId, record.trustState.name)
            .putString(KEY_PREFIX_PUBKEY + record.nodeId, Base64.getEncoder().encodeToString(record.publicKeyBytes))
            .apply {
                if (record.verifiedAtMs != null) {
                    putLong(KEY_PREFIX_TIME + record.nodeId, record.verifiedAtMs)
                } else {
                    remove(KEY_PREFIX_TIME + record.nodeId)
                }
                if (record.customLabel != null) {
                    putString(KEY_PREFIX_LABEL + record.nodeId, record.customLabel)
                } else {
                    remove(KEY_PREFIX_LABEL + record.nodeId)
                }
            }
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "org.nearby.mesh.peer_trust_prefs"
        private const val KEY_PREFIX_STATE = "trust_state_"
        private const val KEY_PREFIX_PUBKEY = "trust_pubkey_"
        private const val KEY_PREFIX_TIME = "trust_time_"
        private const val KEY_PREFIX_LABEL = "trust_label_"
    }
}
