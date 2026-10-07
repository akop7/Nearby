package org.nearby.mesh.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Identifies the purpose and routing behavior of a [MeshPacket].
 */
enum class PacketType(val code: Byte) {
    BEACON_PING(0x01),
    DISCOVERY_ANNOUNCE(0x02),
    TEXT_MESSAGE(0x03),
    SOS_ALERT(0x04),
    TRANSFER_OFFER(0x05),
    TRANSFER_ACCEPT(0x06),
    NOISE_HANDSHAKE(0x07),
    ACK(0x08);

    companion object {
        private val CODE_MAP = entries.associateBy { it.code }

        fun fromCode(code: Byte): PacketType? = CODE_MAP[code]
    }
}

/**
 * Representation of a wire packet routed through the Nearby mesh network.
 *
 * Designed with a fixed binary header for minimal overhead over BLE MTU constraints.
 */
data class MeshPacket(
    val version: Byte = CURRENT_VERSION,
    val type: PacketType,
    val ttl: Byte,
    val hopCount: Byte,
    val packetId: Long,
    val senderNodeId: String,
    val destNodeId: String,
    val chunkIndex: Short = 0,
    val chunkCount: Short = 1,
    val payload: ByteArray
) {
    init {
        require(senderNodeId.length == 16) { "senderNodeId must be 16 hex characters" }
        require(destNodeId.length == 16) { "destNodeId must be 16 hex characters" }
        require(chunkIndex >= 0) { "chunkIndex cannot be negative" }
        require(chunkCount >= 1) { "chunkCount must be at least 1" }
        require(chunkIndex < chunkCount) { "chunkIndex must be less than chunkCount" }
        require(chunkCount <= MAX_CHUNK_COUNT) { "chunkCount exceeds safety limit of $MAX_CHUNK_COUNT" }
        require(payload.size <= MAX_PAYLOAD_PER_CHUNK) { "payload size (${payload.size}) exceeds max $MAX_PAYLOAD_PER_CHUNK" }
    }

    /**
     * Prepares this packet for relay by decrementing TTL and incrementing hop count.
     * Returns null if TTL is exhausted (<= 1) or exceeds max bounds.
     */
    fun forRelay(): MeshPacket? {
        if (ttl <= 1) return null
        val nextHop = (hopCount + 1).toByte()
        val nextTtl = (ttl - 1).toByte()
        return copy(
            ttl = nextTtl,
            hopCount = nextHop
        )
    }

    val isBroadcast: Boolean
        get() = destNodeId.equals(BROADCAST_NODE_ID, ignoreCase = true)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MeshPacket

        if (version != other.version) return false
        if (type != other.type) return false
        if (ttl != other.ttl) return false
        if (hopCount != other.hopCount) return false
        if (packetId != other.packetId) return false
        if (senderNodeId != other.senderNodeId) return false
        if (destNodeId != other.destNodeId) return false
        if (chunkIndex != other.chunkIndex) return false
        if (chunkCount != other.chunkCount) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = version.toInt()
        result = 31 * result + type.hashCode()
        result = 31 * result + ttl.toInt()
        result = 31 * result + hopCount.toInt()
        result = 31 * result + packetId.hashCode()
        result = 31 * result + senderNodeId.hashCode()
        result = 31 * result + destNodeId.hashCode()
        result = 31 * result + chunkIndex.toInt()
        result = 31 * result + chunkCount.toInt()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val CURRENT_VERSION: Byte = 0x01
        const val MAGIC_0: Byte = 0x4E // 'N'
        const val MAGIC_1: Byte = 0x42 // 'B'
        const val HEADER_SIZE: Int = 36
        const val BROADCAST_NODE_ID = "ffffffffffffffff"
        const val DEFAULT_TTL: Byte = 7
        const val SOS_TTL: Byte = 15
        const val MAX_CHUNK_COUNT: Short = 256
        const val MAX_PAYLOAD_PER_CHUNK: Int = 512
        const val BLE_SAFE_PAYLOAD_SIZE: Int = 180
    }
}

/**
 * Encodes and decodes [MeshPacket] to/from binary bytes with strict bounds checking (Rules.md §5).
 */
object MeshPacketCodec {

    fun encode(packet: MeshPacket): ByteArray {
        val buffer = ByteBuffer.allocate(MeshPacket.HEADER_SIZE + packet.payload.size)
        buffer.order(ByteOrder.BIG_ENDIAN)

        // Magic (2) + Version (1) + Type (1)
        buffer.put(MeshPacket.MAGIC_0)
        buffer.put(MeshPacket.MAGIC_1)
        buffer.put(packet.version)
        buffer.put(packet.type.code)

        // TTL (1) + HopCount (1)
        buffer.put(packet.ttl)
        buffer.put(packet.hopCount)

        // PacketId (8)
        buffer.putLong(packet.packetId)

        // SenderNodeId (8 bytes from 16 hex chars)
        buffer.put(hexToBytes(packet.senderNodeId))

        // DestNodeId (8 bytes from 16 hex chars)
        buffer.put(hexToBytes(packet.destNodeId))

        // ChunkIndex (2) + ChunkCount (2)
        buffer.putShort(packet.chunkIndex)
        buffer.putShort(packet.chunkCount)

        // PayloadLength (2)
        buffer.putShort(packet.payload.size.toShort())

        // Payload bytes
        buffer.put(packet.payload)

        return buffer.array()
    }

    fun decode(bytes: ByteArray): MeshPacket? {
        if (bytes.size < MeshPacket.HEADER_SIZE) return null

        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.BIG_ENDIAN)

        val m0 = buffer.get()
        val m1 = buffer.get()
        if (m0 != MeshPacket.MAGIC_0 || m1 != MeshPacket.MAGIC_1) {
            return null
        }

        val version = buffer.get()
        if (version != MeshPacket.CURRENT_VERSION) {
            return null
        }

        val typeCode = buffer.get()
        val type = PacketType.fromCode(typeCode) ?: return null

        val ttl = buffer.get()
        val hopCount = buffer.get()
        val packetId = buffer.getLong()

        val senderBytes = ByteArray(8)
        buffer.get(senderBytes)
        val senderNodeId = bytesToHex(senderBytes)

        val destBytes = ByteArray(8)
        buffer.get(destBytes)
        val destNodeId = bytesToHex(destBytes)

        val chunkIndex = buffer.getShort()
        val chunkCount = buffer.getShort()

        // Bounds checks per Rules.md §5
        if (chunkIndex < 0 || chunkCount <= 0 || chunkIndex >= chunkCount || chunkCount > MeshPacket.MAX_CHUNK_COUNT) {
            return null
        }

        val payloadLength = buffer.getShort().toInt() and 0xFFFF
        if (payloadLength < 0 || payloadLength > MeshPacket.MAX_PAYLOAD_PER_CHUNK) {
            return null
        }

        if (buffer.remaining() < payloadLength) {
            return null
        }

        val payload = ByteArray(payloadLength)
        buffer.get(payload)

        return MeshPacket(
            version = version,
            type = type,
            ttl = ttl,
            hopCount = hopCount,
            packetId = packetId,
            senderNodeId = senderNodeId,
            destNodeId = destNodeId,
            chunkIndex = chunkIndex,
            chunkCount = chunkCount,
            payload = payload
        )
    }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(8)
        for (i in 0 until 8) {
            val high = Character.digit(hex[i * 2], 16)
            val low = Character.digit(hex[i * 2 + 1], 16)
            result[i] = ((high shl 4) or low).toByte()
        }
        return result
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(16)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}

/**
 * Splits large message payloads into sequence of chunks suitable for BLE MTU limits.
 */
object MessageChunker {
    private val secureRandom = SecureRandom()

    fun chunkMessage(
        type: PacketType,
        ttl: Byte,
        senderNodeId: String,
        destNodeId: String,
        payload: ByteArray,
        chunkSize: Int = MeshPacket.BLE_SAFE_PAYLOAD_SIZE,
        packetId: Long = secureRandom.nextLong()
    ): List<MeshPacket> {
        if (payload.isEmpty()) {
            return listOf(
                MeshPacket(
                    type = type,
                    ttl = ttl,
                    hopCount = 0,
                    packetId = packetId,
                    senderNodeId = senderNodeId,
                    destNodeId = destNodeId,
                    chunkIndex = 0,
                    chunkCount = 1,
                    payload = ByteArray(0)
                )
            )
        }

        val safeChunkSize = chunkSize.coerceIn(1, MeshPacket.MAX_PAYLOAD_PER_CHUNK)
        val chunkCount = ((payload.size + safeChunkSize - 1) / safeChunkSize).toShort()
        require(chunkCount <= MeshPacket.MAX_CHUNK_COUNT) {
            "Payload size (${payload.size}) requires $chunkCount chunks, exceeding max ${MeshPacket.MAX_CHUNK_COUNT}"
        }

        val packets = ArrayList<MeshPacket>(chunkCount.toInt())
        for (i in 0 until chunkCount) {
            val start = i * safeChunkSize
            val end = (start + safeChunkSize).coerceAtMost(payload.size)
            val chunkPayload = payload.copyOfRange(start, end)

            packets.add(
                MeshPacket(
                    type = type,
                    ttl = ttl,
                    hopCount = 0,
                    packetId = packetId,
                    senderNodeId = senderNodeId,
                    destNodeId = destNodeId,
                    chunkIndex = i.toShort(),
                    chunkCount = chunkCount,
                    payload = chunkPayload
                )
            )
        }

        return packets
    }
}

/**
 * Thread-safe in-memory reassembler for incoming multi-chunk packets.
 */
class PacketReassembler(
    private val expiryDurationMs: Long = 60_000L
) {
    private class IncompleteMessage(
        val chunkCount: Short,
        val creationTimestamp: Long = System.currentTimeMillis()
    ) {
        val chunks = ConcurrentHashMap<Short, ByteArray>()
    }

    private val pendingMessages = ConcurrentHashMap<Long, IncompleteMessage>()

    /**
     * Adds an incoming chunk. Returns the fully reassembled payload if all chunks have arrived,
     * or null if still awaiting remaining chunks.
     */
    fun addChunk(packet: MeshPacket): ByteArray? {
        pruneExpired()

        if (packet.chunkCount <= 1) {
            return packet.payload
        }

        val incomplete = pendingMessages.computeIfAbsent(packet.packetId) {
            IncompleteMessage(packet.chunkCount)
        }

        incomplete.chunks[packet.chunkIndex] = packet.payload

        if (incomplete.chunks.size == incomplete.chunkCount.toInt()) {
            pendingMessages.remove(packet.packetId)

            val totalSize = (0 until incomplete.chunkCount).sumOf { incomplete.chunks[it.toShort()]?.size ?: 0 }
            val assembled = ByteBuffer.allocate(totalSize)
            for (i in 0 until incomplete.chunkCount) {
                incomplete.chunks[i.toShort()]?.let { assembled.put(it) }
            }
            return assembled.array()
        }

        return null
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        val iterator = pendingMessages.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.creationTimestamp > expiryDurationMs) {
                iterator.remove()
            }
        }
    }
}
