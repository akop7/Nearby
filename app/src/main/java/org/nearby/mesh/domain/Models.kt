package org.nearby.mesh.domain

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Geographical coordinate location attached to emergency alerts or shared manually.
 */
data class GeoCoordinates(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val accuracyMeters: Float? = null
)

/**
 * Message delivery progression states across the multi-hop mesh.
 */
enum class DeliveryStatus {
    SENDING,
    SENT,
    RELAYED,
    DELIVERED,
    FAILED
}

/**
 * Type of multimedia or file attachment associated with a message.
 */
enum class AttachmentType {
    NONE,
    VOICE_NOTE,
    FILE
}

/**
 * Direct 1-on-1 chat message between mesh nodes.
 */
data class TextMessage(
    val messageId: Long,
    val senderNodeId: String,
    val destNodeId: String,
    val senderDisplayName: String,
    val content: String,
    val timestampMs: Long,
    val isOutgoing: Boolean,
    val deliveryStatus: DeliveryStatus = DeliveryStatus.SENT,
    val isEncrypted: Boolean = true,
    val hopCount: Int = 0,
    val attachmentType: AttachmentType = AttachmentType.NONE,
    val attachmentPath: String? = null,
    val attachmentName: String? = null,
    val attachmentSizeBytes: Long = 0L,
    val attachmentDurationMs: Long = 0L,
    val transferId: String? = null
)

/**
 * Emergency distress alert broadcast across the mesh network.
 */
data class SosAlert(
    val alertId: Long,
    val senderNodeId: String,
    val senderDisplayName: String,
    val timestampMs: Long,
    val batteryPercent: Int,
    val location: GeoCoordinates? = null,
    val distressMessage: String = "EMERGENCY: Assistance Needed",
    val isMedicalEmergency: Boolean = false,
    val isTrapped: Boolean = false,
    val hopCount: Int = 0
)

/**
 * Compact binary encoder and decoder for [SosAlert] payloads over BLE packets.
 */
object SosCodec {
    private const val SOS_MAGIC_0: Byte = 0x53 // 'S'
    private const val SOS_MAGIC_1: Byte = 0x4F // 'O'
    private const val FLAG_HAS_LOCATION: Byte = 0x01
    private const val FLAG_MEDICAL: Byte = 0x02
    private const val FLAG_TRAPPED: Byte = 0x04

    fun encode(alert: SosAlert): ByteArray {
        val nameBytes = alert.senderDisplayName.toByteArray(Charsets.UTF_8).take(32).toByteArray()
        val messageBytes = alert.distressMessage.toByteArray(Charsets.UTF_8).take(256).toByteArray()

        val hasLoc = alert.location != null
        val locSize = if (hasLoc) (8 + 8 + 4 + 4) else 0 // lat + lon + alt + acc
        val totalSize = 2 + 8 + 1 + 1 + locSize + 1 + nameBytes.size + 2 + messageBytes.size

        val buffer = ByteBuffer.allocate(totalSize)
        buffer.order(ByteOrder.BIG_ENDIAN)

        buffer.put(SOS_MAGIC_0)
        buffer.put(SOS_MAGIC_1)
        buffer.putLong(alert.timestampMs)
        buffer.put(alert.batteryPercent.coerceIn(0, 100).toByte())

        var flags: Byte = 0
        if (hasLoc) flags = (flags.toInt() or FLAG_HAS_LOCATION.toInt()).toByte()
        if (alert.isMedicalEmergency) flags = (flags.toInt() or FLAG_MEDICAL.toInt()).toByte()
        if (alert.isTrapped) flags = (flags.toInt() or FLAG_TRAPPED.toInt()).toByte()
        buffer.put(flags)

        if (hasLoc) {
            val loc = alert.location!!
            buffer.putDouble(loc.latitude)
            buffer.putDouble(loc.longitude)
            buffer.putFloat(loc.altitudeMeters?.toFloat() ?: -1f)
            buffer.putFloat(loc.accuracyMeters ?: -1f)
        }

        buffer.put(nameBytes.size.toByte())
        buffer.put(nameBytes)

        buffer.putShort(messageBytes.size.toShort())
        buffer.put(messageBytes)

        return buffer.array()
    }

    fun decode(alertId: Long, senderNodeId: String, hopCount: Int, bytes: ByteArray): SosAlert? {
        if (bytes.size < 14) return null

        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.BIG_ENDIAN)

        val m0 = buffer.get()
        val m1 = buffer.get()
        if (m0 != SOS_MAGIC_0 || m1 != SOS_MAGIC_1) return null

        val timestamp = buffer.getLong()
        val battery = buffer.get().toInt() and 0xFF
        val flags = buffer.get()

        val hasLocation = (flags.toInt() and FLAG_HAS_LOCATION.toInt()) != 0
        val isMedical = (flags.toInt() and FLAG_MEDICAL.toInt()) != 0
        val isTrapped = (flags.toInt() and FLAG_TRAPPED.toInt()) != 0

        val location = if (hasLocation) {
            if (buffer.remaining() < 24) return null
            val lat = buffer.getDouble()
            val lon = buffer.getDouble()
            val alt = buffer.getFloat().let { if (it < 0f) null else it.toDouble() }
            val acc = buffer.getFloat().let { if (it < 0f) null else it }
            GeoCoordinates(lat, lon, alt, acc)
        } else null

        if (buffer.remaining() < 1) return null
        val nameLen = buffer.get().toInt() and 0xFF
        if (buffer.remaining() < nameLen) return null
        val nameBytes = ByteArray(nameLen)
        buffer.get(nameBytes)
        val displayName = String(nameBytes, Charsets.UTF_8)

        if (buffer.remaining() < 2) return null
        val msgLen = buffer.getShort().toInt() and 0xFFFF
        if (buffer.remaining() < msgLen) return null
        val msgBytes = ByteArray(msgLen)
        buffer.get(msgBytes)
        val distressMessage = String(msgBytes, Charsets.UTF_8)

        return SosAlert(
            alertId = alertId,
            senderNodeId = senderNodeId,
            senderDisplayName = displayName,
            timestampMs = timestamp,
            batteryPercent = battery,
            location = location,
            distressMessage = distressMessage,
            isMedicalEmergency = isMedical,
            isTrapped = isTrapped,
            hopCount = hopCount
        )
    }
}

/**
 * Compact binary encoder and decoder for [TextMessage] content payloads.
 */
object TextMessageCodec {
    private const val TEXT_MAGIC_0: Byte = 0x54 // 'T'
    private const val TEXT_MAGIC_1: Byte = 0x58 // 'X'

    fun encode(senderDisplayName: String, content: String, timestampMs: Long): ByteArray {
        val nameBytes = senderDisplayName.toByteArray(Charsets.UTF_8).take(32).toByteArray()
        val contentBytes = content.toByteArray(Charsets.UTF_8)

        val buffer = ByteBuffer.allocate(2 + 8 + 1 + nameBytes.size + 2 + contentBytes.size)
        buffer.order(ByteOrder.BIG_ENDIAN)

        buffer.put(TEXT_MAGIC_0)
        buffer.put(TEXT_MAGIC_1)
        buffer.putLong(timestampMs)

        buffer.put(nameBytes.size.toByte())
        buffer.put(nameBytes)

        buffer.putShort(contentBytes.size.toShort())
        buffer.put(contentBytes)

        return buffer.array()
    }

    fun decode(
        messageId: Long,
        senderNodeId: String,
        destNodeId: String,
        isOutgoing: Boolean,
        hopCount: Int,
        bytes: ByteArray
    ): TextMessage? {
        if (bytes.size < 13) return null

        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.BIG_ENDIAN)

        val m0 = buffer.get()
        val m1 = buffer.get()
        if (m0 != TEXT_MAGIC_0 || m1 != TEXT_MAGIC_1) return null

        val timestamp = buffer.getLong()

        if (buffer.remaining() < 1) return null
        val nameLen = buffer.get().toInt() and 0xFF
        if (buffer.remaining() < nameLen) return null
        val nameBytes = ByteArray(nameLen)
        buffer.get(nameBytes)
        val senderDisplayName = String(nameBytes, Charsets.UTF_8)

        if (buffer.remaining() < 2) return null
        val contentLen = buffer.getShort().toInt() and 0xFFFF
        if (buffer.remaining() < contentLen) return null
        val contentBytes = ByteArray(contentLen)
        buffer.get(contentBytes)
        val content = String(contentBytes, Charsets.UTF_8)

        return TextMessage(
            messageId = messageId,
            senderNodeId = senderNodeId,
            destNodeId = destNodeId,
            senderDisplayName = senderDisplayName,
            content = content,
            timestampMs = timestamp,
            isOutgoing = isOutgoing,
            deliveryStatus = if (isOutgoing) DeliveryStatus.SENT else DeliveryStatus.DELIVERED,
            isEncrypted = true,
            hopCount = hopCount
        )
    }
}
