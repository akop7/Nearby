package org.nearby.mesh.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nearby.mesh.domain.GeoCoordinates
import org.nearby.mesh.domain.SosAlert
import org.nearby.mesh.domain.SosCodec
import org.nearby.mesh.domain.TextMessage
import org.nearby.mesh.domain.TextMessageCodec
import org.nearby.mesh.protocol.MeshPacket
import org.nearby.mesh.protocol.PacketType

class MeshRouterTest {

    private val localNodeId = "0123456789abcdef"
    private val peerNodeId = "fedcba9876543210"
    private val otherNodeId = "aabbccddeeff0011"

    @Test
    fun lruDedupCache_deduplicatesSeenPackets() {
        val cache = LruDedupCache(capacity = 5, ttlMs = 60_000L)
        assertFalse(cache.isSeen(100L))

        assertTrue(cache.markSeen(100L))
        assertTrue(cache.isSeen(100L))

        // Second mark returns false (already present)
        assertFalse(cache.markSeen(100L))
    }

    @Test
    fun lruDedupCache_evictsOldestEntriesWhenFull() {
        val cache = LruDedupCache(capacity = 3, ttlMs = 60_000L)
        cache.markSeen(1L)
        cache.markSeen(2L)
        cache.markSeen(3L)

        assertEquals(3, cache.size)
        assertTrue(cache.isSeen(1L))
        assertTrue(cache.isSeen(2L))
        assertTrue(cache.isSeen(3L))

        // Adding 4th should evict 1L
        cache.markSeen(4L)
        assertEquals(3, cache.size)
        assertFalse(cache.isSeen(1L))
        assertTrue(cache.isSeen(2L))
        assertTrue(cache.isSeen(3L))
        assertTrue(cache.isSeen(4L))
    }

    @Test
    fun routeIncoming_dropsDuplicatePackets() {
        val router = MeshRouter(localNodeId)
        val packet = MeshPacket(
            type = PacketType.TEXT_MESSAGE,
            ttl = 5.toByte(),
            hopCount = 0.toByte(),
            packetId = 12345L,
            senderNodeId = peerNodeId,
            destNodeId = localNodeId,
            payload = "Hello".toByteArray()
        )

        val firstAction = router.routeIncomingPacket(packet)
        assertTrue(firstAction is RoutingAction.DeliverLocal)

        val secondAction = router.routeIncomingPacket(packet)
        assertTrue(secondAction is RoutingAction.Drop)
        assertEquals(DropReason.DUPLICATE, (secondAction as RoutingAction.Drop).reason)
    }

    @Test
    fun routeIncoming_dropsSelfOriginatedPackets() {
        val router = MeshRouter(localNodeId)
        val packet = MeshPacket(
            type = PacketType.TEXT_MESSAGE,
            ttl = 5.toByte(),
            hopCount = 1.toByte(),
            packetId = 999L,
            senderNodeId = localNodeId,
            destNodeId = peerNodeId,
            payload = "Loopback test".toByteArray()
        )

        val action = router.routeIncomingPacket(packet)
        assertTrue(action is RoutingAction.Drop)
        assertEquals(DropReason.SELF_ORIGINATED, (action as RoutingAction.Drop).reason)
    }

    @Test
    fun routeIncoming_dropsWhenTtlExpiredForRelay() {
        val router = MeshRouter(localNodeId)
        val packet = MeshPacket(
            type = PacketType.TEXT_MESSAGE,
            ttl = 1.toByte(), // TTL <= 1 cannot be relayed further
            hopCount = 3.toByte(),
            packetId = 888L,
            senderNodeId = peerNodeId,
            destNodeId = otherNodeId,
            payload = "Relay packet".toByteArray()
        )

        val action = router.routeIncomingPacket(packet)
        assertTrue(action is RoutingAction.Drop)
        assertEquals(DropReason.TTL_EXHAUSTED, (action as RoutingAction.Drop).reason)
    }

    @Test
    fun routeIncoming_relaysWithDecrementedTtlAndIncrementedHops() {
        val router = MeshRouter(localNodeId)
        val packet = MeshPacket(
            type = PacketType.TEXT_MESSAGE,
            ttl = 7.toByte(),
            hopCount = 1.toByte(),
            packetId = 777L,
            senderNodeId = peerNodeId,
            destNodeId = otherNodeId,
            payload = "Multi-hop message".toByteArray()
        )

        val action = router.routeIncomingPacket(packet)
        assertTrue(action is RoutingAction.ForwardRelay)

        val relayed = (action as RoutingAction.ForwardRelay).relayPacket
        assertEquals(6.toByte(), relayed.ttl)
        assertEquals(2.toByte(), relayed.hopCount)
        assertEquals(packet.packetId, relayed.packetId)
        assertEquals(packet.senderNodeId, relayed.senderNodeId)
        assertEquals(packet.destNodeId, relayed.destNodeId)
    }

    @Test
    fun routeIncoming_broadcastDeliversLocallyAndRelays() {
        val router = MeshRouter(localNodeId)
        val packet = MeshPacket(
            type = PacketType.SOS_ALERT,
            ttl = 5.toByte(),
            hopCount = 0.toByte(),
            packetId = 555L,
            senderNodeId = peerNodeId,
            destNodeId = MeshPacket.BROADCAST_NODE_ID,
            payload = "SOS".toByteArray()
        )

        val action = router.routeIncomingPacket(packet)
        assertTrue(action is RoutingAction.DeliverAndForward)

        val deliverAndForward = action as RoutingAction.DeliverAndForward
        assertEquals(555L, deliverAndForward.localPacket.packetId)
        assertEquals(4.toByte(), deliverAndForward.relayPacket.ttl)
        assertEquals(1.toByte(), deliverAndForward.relayPacket.hopCount)
    }

    @Test
    fun priorityQueue_prioritizesSosOverRoutineTraffic() {
        val queue = PriorityPacketQueue()

        val textPacket = MeshPacket(
            type = PacketType.TEXT_MESSAGE,
            ttl = 7.toByte(),
            hopCount = 0.toByte(),
            packetId = 1L,
            senderNodeId = localNodeId,
            destNodeId = peerNodeId,
            payload = "Chat".toByteArray()
        )

        val sosPacket = MeshPacket(
            type = PacketType.SOS_ALERT,
            ttl = 7.toByte(),
            hopCount = 0.toByte(),
            packetId = 2L,
            senderNodeId = localNodeId,
            destNodeId = MeshPacket.BROADCAST_NODE_ID,
            payload = "Emergency".toByteArray()
        )

        val handshakePacket = MeshPacket(
            type = PacketType.NOISE_HANDSHAKE,
            ttl = 1.toByte(),
            hopCount = 0.toByte(),
            packetId = 3L,
            senderNodeId = localNodeId,
            destNodeId = peerNodeId,
            payload = ByteArray(32)
        )

        // Enqueue routine text first, then handshake, then SOS
        queue.enqueue(textPacket)
        queue.enqueue(handshakePacket)
        queue.enqueue(sosPacket)

        assertEquals(3, queue.size())

        // SOS must pop first
        val first = queue.pollImmediate()
        assertNotNull(first)
        assertEquals(PacketType.SOS_ALERT, first?.type)
        assertEquals(2L, first?.packetId)

        // Noise handshake must pop second
        val second = queue.pollImmediate()
        assertNotNull(second)
        assertEquals(PacketType.NOISE_HANDSHAKE, second?.type)
        assertEquals(3L, second?.packetId)

        // Text message must pop last
        val third = queue.pollImmediate()
        assertNotNull(third)
        assertEquals(PacketType.TEXT_MESSAGE, third?.type)
        assertEquals(1L, third?.packetId)
    }

    @Test
    fun priorityQueue_sosBypassesRoutineRateLimiting() {
        val queue = PriorityPacketQueue(routineIntervalMs = 500L)
        val textPacket1 = MeshPacket(
            type = PacketType.TEXT_MESSAGE,
            ttl = 7.toByte(),
            hopCount = 0.toByte(),
            packetId = 1L,
            senderNodeId = localNodeId,
            destNodeId = peerNodeId,
            payload = "Chat 1".toByteArray()
        )
        val textPacket2 = MeshPacket(
            type = PacketType.TEXT_MESSAGE,
            ttl = 7.toByte(),
            hopCount = 0.toByte(),
            packetId = 2L,
            senderNodeId = localNodeId,
            destNodeId = peerNodeId,
            payload = "Chat 2".toByteArray()
        )
        val sosPacket = MeshPacket(
            type = PacketType.SOS_ALERT,
            ttl = 7.toByte(),
            hopCount = 0.toByte(),
            packetId = 3L,
            senderNodeId = localNodeId,
            destNodeId = MeshPacket.BROADCAST_NODE_ID,
            payload = "Emergency".toByteArray()
        )

        queue.enqueue(textPacket1)
        queue.enqueue(textPacket2)
        queue.enqueue(sosPacket)

        val t0 = 1000L
        // SOS pops first regardless of time
        val p1 = queue.poll(currentTimeMs = t0)
        assertNotNull(p1)
        assertEquals(PacketType.SOS_ALERT, p1?.type)

        // Next routine packet pops
        val p2 = queue.poll(currentTimeMs = t0)
        assertNotNull(p2)
        assertEquals(PacketType.TEXT_MESSAGE, p2?.type)
        assertEquals(1L, p2?.packetId)

        // Immediate next routine packet within 500ms is throttled (returns null)
        val p3Throttled = queue.poll(currentTimeMs = t0 + 100L)
        assertNull(p3Throttled)

        // After 500ms, second routine packet pops
        val p3Allowed = queue.poll(currentTimeMs = t0 + 501L)
        assertNotNull(p3Allowed)
        assertEquals(2L, p3Allowed?.packetId)
    }

    @Test
    fun sosCodec_roundTripsCorrectlyWithLocation() {
        val alert = SosAlert(
            alertId = 987654321L,
            senderNodeId = localNodeId,
            senderDisplayName = "Survivor-1234",
            timestampMs = 1700000000000L,
            batteryPercent = 84,
            location = GeoCoordinates(
                latitude = 37.774929,
                longitude = -122.419416,
                altitudeMeters = 12.5,
                accuracyMeters = 4.2f
            ),
            distressMessage = "Need food and water immediately",
            isMedicalEmergency = true,
            isTrapped = false,
            hopCount = 0
        )

        val encoded = SosCodec.encode(alert)
        val decoded = SosCodec.decode(
            alertId = alert.alertId,
            senderNodeId = localNodeId,
            hopCount = 2,
            bytes = encoded
        )

        assertNotNull(decoded)
        assertEquals(alert.alertId, decoded?.alertId)
        assertEquals(localNodeId, decoded?.senderNodeId)
        assertEquals(alert.senderDisplayName, decoded?.senderDisplayName)
        assertEquals(alert.timestampMs, decoded?.timestampMs)
        assertEquals(alert.batteryPercent, decoded?.batteryPercent)
        assertEquals(alert.distressMessage, decoded?.distressMessage)
        assertTrue(decoded!!.isMedicalEmergency)
        assertFalse(decoded.isTrapped)
        assertEquals(2, decoded.hopCount)

        assertNotNull(decoded.location)
        assertEquals(37.774929, decoded.location!!.latitude, 0.00001)
        assertEquals(-122.419416, decoded.location!!.longitude, 0.00001)
    }

    @Test
    fun sosCodec_roundTripsCorrectlyWithoutLocation() {
        val alert = SosAlert(
            alertId = 555555L,
            senderNodeId = peerNodeId,
            senderDisplayName = "Survivor-9999",
            timestampMs = 1700000050000L,
            batteryPercent = 42,
            location = null,
            distressMessage = "Trapped in basement",
            isMedicalEmergency = false,
            isTrapped = true,
            hopCount = 0
        )

        val encoded = SosCodec.encode(alert)
        val decoded = SosCodec.decode(
            alertId = alert.alertId,
            senderNodeId = peerNodeId,
            hopCount = 1,
            bytes = encoded
        )

        assertNotNull(decoded)
        assertEquals(alert.alertId, decoded?.alertId)
        assertEquals(peerNodeId, decoded?.senderNodeId)
        assertEquals("Survivor-9999", decoded?.senderDisplayName)
        assertEquals(42, decoded?.batteryPercent)
        assertNull(decoded?.location)
        assertFalse(decoded!!.isMedicalEmergency)
        assertTrue(decoded.isTrapped)
        assertEquals(1, decoded.hopCount)
    }

    @Test
    fun textMessageCodec_roundTripsCorrectly() {
        val now = 1700000000000L
        val encoded = TextMessageCodec.encode(
            senderDisplayName = "Survivor-ABCD",
            content = "Are you safe near the river bridge?",
            timestampMs = now
        )

        val decoded = TextMessageCodec.decode(
            messageId = 123456L,
            senderNodeId = localNodeId,
            destNodeId = peerNodeId,
            isOutgoing = false,
            hopCount = 1,
            bytes = encoded
        )

        assertNotNull(decoded)
        assertEquals(123456L, decoded?.messageId)
        assertEquals(localNodeId, decoded?.senderNodeId)
        assertEquals(peerNodeId, decoded?.destNodeId)
        assertEquals("Survivor-ABCD", decoded?.senderDisplayName)
        assertEquals("Are you safe near the river bridge?", decoded?.content)
        assertEquals(now, decoded?.timestampMs)
        assertEquals(1, decoded?.hopCount)
        assertFalse(decoded!!.isOutgoing)
    }

    @Test
    fun multiHop3NodeSimulation_routesFromAToThroughB() {
        val nodeA = "1111111111111111"
        val nodeB = "2222222222222222"
        val nodeC = "3333333333333333"

        val routerA = MeshRouter(localNodeId = nodeA)
        val routerB = MeshRouter(localNodeId = nodeB)
        val routerC = MeshRouter(localNodeId = nodeC)

        // 1. Node A creates a packet for Node C
        val packetA = MeshPacket(
            type = PacketType.TEXT_MESSAGE,
            ttl = 7.toByte(),
            hopCount = 0.toByte(),
            packetId = 10001L,
            senderNodeId = nodeA,
            destNodeId = nodeC,
            payload = "Hello from A to C".toByteArray()
        )

        // 2. Node B receives it from the air
        val actionB = routerB.routeIncomingPacket(packetA)
        assertTrue(actionB is RoutingAction.ForwardRelay)
        val relayedByB = (actionB as RoutingAction.ForwardRelay).relayPacket
        assertEquals(6.toByte(), relayedByB.ttl)
        assertEquals(1.toByte(), relayedByB.hopCount)
        assertEquals(nodeA, relayedByB.senderNodeId)
        assertEquals(nodeC, relayedByB.destNodeId)

        // 3. Node C receives the relayed packet from B
        val actionC = routerC.routeIncomingPacket(relayedByB)
        assertTrue(actionC is RoutingAction.DeliverLocal)
        val deliveredAtC = (actionC as RoutingAction.DeliverLocal).packet
        assertEquals(10001L, deliveredAtC.packetId)
        assertEquals(1.toByte(), deliveredAtC.hopCount)

        // 4. If Node B receives the same packet again from another relay, it drops it as duplicate
        val duplicateAtB = routerB.routeIncomingPacket(relayedByB)
        assertTrue(duplicateAtB is RoutingAction.Drop)
        assertEquals(DropReason.DUPLICATE, (duplicateAtB as RoutingAction.Drop).reason)
    }
}
