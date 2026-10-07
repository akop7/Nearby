package org.nearby.mesh.routing

import org.nearby.mesh.protocol.MeshPacket
import org.nearby.mesh.protocol.PacketType
import java.util.Collections
import java.util.LinkedHashMap
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe LRU deduplication cache for tracking seen packet IDs.
 * Bounded by [maxEntries] to prevent memory exhaustion and [ttlExpiryMs] to prune old entries.
 */
class LruDedupCache(
    val capacity: Int = DEFAULT_MAX_ENTRIES,
    val ttlMs: Long = DEFAULT_TTL_EXPIRY_MS
) {
    // LinkedHashMap in access-order synchronized for thread-safety
    private val map = Collections.synchronizedMap(
        object : LinkedHashMap<Long, Long>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean {
                return size > capacity
            }
        }
    )

    fun isSeen(packetId: Long, currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        val lastSeen = map[packetId] ?: return false
        return (currentTimeMs - lastSeen) <= ttlMs
    }

    /**
     * Checks if [packetId] has been seen recently within the TTL window.
     * Returns true if seen (duplicate), false if new.
     */
    fun isDuplicate(packetId: Long, currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        if (isSeen(packetId, currentTimeMs)) {
            return true
        }
        map[packetId] = currentTimeMs
        return false
    }

    /**
     * Marks [packetId] as seen. Returns true if newly added, false if already seen.
     */
    fun markSeen(packetId: Long, currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        val seen = isSeen(packetId, currentTimeMs)
        map[packetId] = currentTimeMs
        return !seen
    }

    /**
     * Explicit check without mutating cache.
     */
    fun contains(packetId: Long, currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        return isSeen(packetId, currentTimeMs)
    }

    fun evictExpired(currentTimeMs: Long = System.currentTimeMillis()) {
        synchronized(map) {
            val iterator = map.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (currentTimeMs - entry.value > ttlMs) {
                    iterator.remove()
                }
            }
        }
    }

    fun clear() {
        map.clear()
    }

    val size: Int get() = map.size

    companion object {
        const val DEFAULT_MAX_ENTRIES = 1000
        const val DEFAULT_TTL_EXPIRY_MS = 120_000L // 2 minutes
    }
}

/**
 * Priority queue item wrapper ensuring packets are ordered strictly by urgency.
 */
data class QueuedPacket(
    val packet: MeshPacket,
    val priority: Int,
    val enqueuedTimeMs: Long,
    val sequenceNumber: Long
) : Comparable<QueuedPacket> {
    override operator fun compareTo(other: QueuedPacket): Int {
        // Lower priority number = higher urgency
        if (this.priority != other.priority) {
            return this.priority.compareTo(other.priority)
        }
        // Older packets within the same priority level go first
        return this.sequenceNumber.compareTo(other.sequenceNumber)
    }
}

/**
 * Prioritized outbound packet queue with rate-limiting support.
 * [PacketType.SOS_ALERT] receives maximum priority (0) and completely bypasses routine rate limits.
 */
class PriorityPacketQueue(
    private val routineIntervalMs: Long = DEFAULT_ROUTINE_INTERVAL_MS
) {
    private val queue = PriorityQueue<QueuedPacket>()
    private val sequenceCounter = AtomicLong(0)
    private var lastRoutineTxTimeMs: Long = 0L

    @Synchronized
    fun enqueue(packet: MeshPacket, enqueuedTimeMs: Long = System.currentTimeMillis()) {
        val priority = getPacketPriority(packet.type)
        queue.offer(
            QueuedPacket(
                packet = packet,
                priority = priority,
                enqueuedTimeMs = enqueuedTimeMs,
                sequenceNumber = sequenceCounter.getAndIncrement()
            )
        )
    }

    /**
     * Retrieves the next packet ready for transmission according to rate-limiting rules.
     * SOS packets are returned immediately without delay.
     * Routine packets return null if the rate limit window has not elapsed.
     */
    @Synchronized
    fun poll(currentTimeMs: Long = System.currentTimeMillis()): MeshPacket? {
        val head = queue.peek() ?: return null

        // SOS packets bypass all rate-limiting
        if (head.priority == PRIORITY_SOS) {
            return queue.poll()?.packet
        }

        // Routine packets observe minimum spacing to prevent BLE radio congestion
        if (currentTimeMs - lastRoutineTxTimeMs >= routineIntervalMs) {
            lastRoutineTxTimeMs = currentTimeMs
            return queue.poll()?.packet
        }

        return null
    }

    /**
     * Force poll without checking rate limits (e.g. for testing or draining).
     */
    @Synchronized
    fun pollImmediate(): MeshPacket? {
        return queue.poll()?.packet
    }

    @Synchronized
    fun peek(): MeshPacket? = queue.peek()?.packet

    @Synchronized
    fun size(): Int = queue.size

    @Synchronized
    fun isEmpty(): Boolean = queue.isEmpty()

    @Synchronized
    fun clear() {
        queue.clear()
        lastRoutineTxTimeMs = 0L
    }

    companion object {
        const val PRIORITY_SOS = 0
        const val PRIORITY_HANDSHAKE = 1
        const val PRIORITY_ACK = 2
        const val PRIORITY_TEXT = 3
        const val PRIORITY_DEFAULT = 4

        const val DEFAULT_ROUTINE_INTERVAL_MS = 50L // 50ms between routine BLE writes

        fun getPacketPriority(type: PacketType): Int {
            return when (type) {
                PacketType.SOS_ALERT -> PRIORITY_SOS
                PacketType.NOISE_HANDSHAKE -> PRIORITY_HANDSHAKE
                PacketType.ACK -> PRIORITY_ACK
                PacketType.TEXT_MESSAGE -> PRIORITY_TEXT
                else -> PRIORITY_DEFAULT
            }
        }
    }
}

/**
 * Result of routing an incoming [MeshPacket].
 */
sealed class RoutingAction {
    /** Packet destined for this node or a broadcast payload to be processed locally. */
    data class DeliverLocal(val packet: MeshPacket) : RoutingAction()

    /** Packet must be retransmitted to neighbors (decremented TTL, incremented hop). */
    data class ForwardRelay(val relayPacket: MeshPacket) : RoutingAction()

    /** Broadcast packet: Deliver locally AND forward relay to other mesh nodes. */
    data class DeliverAndForward(val localPacket: MeshPacket, val relayPacket: MeshPacket) : RoutingAction()

    /** Packet dropped (duplicate, self-originated, or TTL exhausted). */
    data class Drop(val reason: DropReason) : RoutingAction()
}

enum class DropReason {
    DUPLICATE,
    SELF_ORIGINATED,
    TTL_EXHAUSTED,
    INVALID_FORMAT
}

/**
 * Core Mesh Routing Engine managing managed flood routing, deduplication, and hop limits.
 */
class MeshRouter(
    val localNodeId: String,
    val dedupCache: LruDedupCache = LruDedupCache(),
    val outboundQueue: PriorityPacketQueue = PriorityPacketQueue()
) {
    /**
     * Evaluates an incoming packet against the routing rules:
     * 1. Ignore if self-originated.
     * 2. Ignore if already seen in deduplication cache.
     * 3. If destination is localNodeId -> DeliverLocal.
     * 4. If destination is BROADCAST -> DeliverAndForward (if TTL permits forward).
     * 5. If destination is another node -> ForwardRelay (if TTL permits forward).
     */
    fun routeIncomingPacket(
        packet: MeshPacket,
        currentTimeMs: Long = System.currentTimeMillis()
    ): RoutingAction {
        // Rule 1: Never process or loop our own packets
        if (packet.senderNodeId.equals(localNodeId, ignoreCase = true)) {
            return RoutingAction.Drop(DropReason.SELF_ORIGINATED)
        }

        // Rule 2: Deduplication check
        if (dedupCache.isDuplicate(packet.packetId, currentTimeMs)) {
            return RoutingAction.Drop(DropReason.DUPLICATE)
        }

        // Rule 3: Broadcast packets (e.g. SOS_ALERT)
        if (packet.isBroadcast) {
            val relayPacket = packet.forRelay()
            return if (relayPacket != null) {
                outboundQueue.enqueue(relayPacket, currentTimeMs)
                RoutingAction.DeliverAndForward(packet, relayPacket)
            } else {
                RoutingAction.DeliverLocal(packet)
            }
        }

        // Rule 4: Direct packet addressed to this node
        if (packet.destNodeId.equals(localNodeId, ignoreCase = true)) {
            return RoutingAction.DeliverLocal(packet)
        }

        // Rule 5: Multi-hop relay for another node
        val relayPacket = packet.forRelay()
        return if (relayPacket != null) {
            outboundQueue.enqueue(relayPacket, currentTimeMs)
            RoutingAction.ForwardRelay(relayPacket)
        } else {
            RoutingAction.Drop(DropReason.TTL_EXHAUSTED)
        }
    }

    /**
     * Enqueues a locally-generated packet for transmission over the mesh.
     */
    fun sendLocalPacket(packet: MeshPacket, currentTimeMs: Long = System.currentTimeMillis()) {
        dedupCache.markSeen(packet.packetId, currentTimeMs)
        outboundQueue.enqueue(packet, currentTimeMs)
    }
}
