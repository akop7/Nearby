package org.nearby.mesh.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe message repository for peer chat timelines and active SOS distress alerts.
 * Pure Kotlin domain layer component with zero Android framework dependencies (Rules.md §3).
 */
class MessageStore {

    // Map of peerNodeId -> MutableStateFlow of messages
    private val timelines = ConcurrentHashMap<String, MutableStateFlow<List<TextMessage>>>()

    // Map of active SOS alerts: alertId -> SosAlert
    private val sosAlertMap = ConcurrentHashMap<Long, SosAlert>()
    private val _activeSosAlerts = MutableStateFlow<List<SosAlert>>(emptyList())
    val activeSosAlerts: StateFlow<List<SosAlert>> = _activeSosAlerts.asStateFlow()

    /**
     * Retrieves or creates a reactive timeline [StateFlow] for the specified [peerNodeId].
     */
    fun getTimeline(peerNodeId: String): StateFlow<List<TextMessage>> {
        return getOrCreateTimelineFlow(peerNodeId).asStateFlow()
    }

    /**
     * Appends a new [TextMessage] to the conversation timeline with the peer.
     */
    @Synchronized
    fun addMessage(message: TextMessage) {
        val peerKey = if (message.isOutgoing) message.destNodeId else message.senderNodeId
        val flow = getOrCreateTimelineFlow(peerKey)

        val currentList = flow.value.toMutableList()
        // Prevent duplicate insertion if already recorded
        val existingIndex = currentList.indexOfFirst { it.messageId == message.messageId }
        if (existingIndex != -1) {
            currentList[existingIndex] = message
        } else {
            currentList.add(message)
        }
        flow.value = currentList
    }

    /**
     * Updates the delivery status of an existing message (e.g. SENT -> RELAYED -> DELIVERED).
     */
    @Synchronized
    fun updateDeliveryStatus(peerNodeId: String, messageId: Long, newStatus: DeliveryStatus) {
        val flow = timelines[peerNodeId] ?: return
        val currentList = flow.value.toMutableList()
        val index = currentList.indexOfFirst { it.messageId == messageId }
        if (index != -1) {
            currentList[index] = currentList[index].copy(deliveryStatus = newStatus)
            flow.value = currentList
        }
    }

    /**
     * Records or updates an active emergency [SosAlert] on the mesh.
     */
    @Synchronized
    fun addSosAlert(alert: SosAlert) {
        sosAlertMap[alert.alertId] = alert
        _activeSosAlerts.value = sosAlertMap.values.sortedByDescending { it.timestampMs }
    }

    /**
     * Dismisses or clears a resolved SOS alert.
     */
    @Synchronized
    fun dismissSosAlert(alertId: Long) {
        if (sosAlertMap.remove(alertId) != null) {
            _activeSosAlerts.value = sosAlertMap.values.sortedByDescending { it.timestampMs }
        }
    }

    /**
     * Clears all messages and alerts (used for testing or data reset).
     */
    @Synchronized
    fun clearAll() {
        timelines.clear()
        sosAlertMap.clear()
        _activeSosAlerts.value = emptyList()
    }

    private fun getOrCreateTimelineFlow(peerNodeId: String): MutableStateFlow<List<TextMessage>> {
        return timelines.computeIfAbsent(peerNodeId) {
            MutableStateFlow(emptyList())
        }
    }
}
