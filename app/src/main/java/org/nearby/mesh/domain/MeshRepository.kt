package org.nearby.mesh.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Represents a discovered nearby peer in the mesh network.
 * Pure Kotlin domain model with zero Android framework dependencies (Rules.md §3).
 */
data class DiscoveredPeer(
    val nodeId: String,
    val displayName: String,
    val rssi: Int,
    val lastSeenMs: Long,
    val trustState: PeerTrustState = PeerTrustState.UNVERIFIED,
    val isEncrypted: Boolean = false,
    val sasCode: String? = null
)

/**
 * Domain-level contract for observing reachable mesh peers, messaging timelines,
 * emergency SOS alerts, and managing mesh lifecycle (Rules.md §3).
 */
interface MeshRepository {
    val peers: StateFlow<List<DiscoveredPeer>>
    val activeSosAlerts: StateFlow<List<SosAlert>>
    val activeTransfers: StateFlow<Map<String, TransferProgress>>
    val audioController: AudioController

    fun start()
    fun stop()
    fun markPeerVerified(nodeId: String, customLabel: String? = null)
    fun markPeerCompromised(nodeId: String)

    fun getTimeline(peerNodeId: String): StateFlow<List<TextMessage>>
    fun sendDirectMessage(peerNodeId: String, text: String): TextMessage
    fun sendFile(peerNodeId: String, file: java.io.File): String?
    fun sendVoiceNote(peerNodeId: String, file: java.io.File, durationMs: Long): String?
    fun cancelTransfer(transferId: String)

    fun broadcastSos(
        distressNote: String = "EMERGENCY: Assistance Needed",
        location: GeoCoordinates? = null,
        isMedical: Boolean = false,
        isTrapped: Boolean = false
    ): SosAlert
    fun dismissSos(alertId: Long)
}
