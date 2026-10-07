package org.nearby.mesh.ble

import org.junit.Assert.assertEquals
import org.junit.Test
import org.nearby.mesh.domain.DiscoveredPeer

class BleMeshEngineTest {

    @Test
    fun discoveredPeer_holdsPropertiesCorrectly() {
        val peer = DiscoveredPeer(
            nodeId = "0123456789abcdef",
            displayName = "Survivor-CDEF",
            rssi = -65,
            lastSeenMs = 1700000000000L
        )

        assertEquals("0123456789abcdef", peer.nodeId)
        assertEquals("Survivor-CDEF", peer.displayName)
        assertEquals(-65, peer.rssi)
        assertEquals(1700000000000L, peer.lastSeenMs)
    }

    @Test
    fun protocolConstants_areProperlyConfigured() {
        assertEquals(0x01.toByte(), BleMeshEngine.PROTOCOL_VERSION)
        assertEquals("0000fe26-0000-1000-8000-00805f9b34fb", BleMeshEngine.SERVICE_UUID.toString())
    }

    @Test
    fun messageDeliveryPipeline_encodeDecodeAck_succeeds() {
        val aliceNodeId = "0123456789abcdef"
        val bobNodeId = "fedcba9876543210"
        val now = System.currentTimeMillis()

        // 1. Text payload encode
        val textBytes = org.nearby.mesh.domain.TextMessageCodec.encode("Alice", "Hello Bob!", now)

        // 2. Chunker
        val packets = org.nearby.mesh.protocol.MessageChunker.chunkMessage(
            type = org.nearby.mesh.protocol.PacketType.TEXT_MESSAGE,
            ttl = 7,
            senderNodeId = aliceNodeId,
            destNodeId = bobNodeId,
            payload = textBytes,
            packetId = 12345L
        )
        assertEquals(1, packets.size)

        // 3. Wire codec encode/decode (simulating GATT transport transfer)
        val wireBytes = org.nearby.mesh.protocol.MeshPacketCodec.encode(packets[0])
        val decodedPacket = org.nearby.mesh.protocol.MeshPacketCodec.decode(wireBytes)
        org.junit.Assert.assertNotNull(decodedPacket)
        assertEquals(org.nearby.mesh.protocol.PacketType.TEXT_MESSAGE, decodedPacket!!.type)
        assertEquals(aliceNodeId, decodedPacket.senderNodeId)
        assertEquals(bobNodeId, decodedPacket.destNodeId)

        // 4. Decode text message payload on receiver
        val decodedMsg = org.nearby.mesh.domain.TextMessageCodec.decode(
            messageId = decodedPacket.packetId,
            senderNodeId = decodedPacket.senderNodeId,
            destNodeId = decodedPacket.destNodeId,
            isOutgoing = false,
            hopCount = 0,
            bytes = decodedPacket.payload
        )
        org.junit.Assert.assertNotNull(decodedMsg)
        assertEquals("Hello Bob!", decodedMsg!!.content)
        assertEquals("Alice", decodedMsg.senderDisplayName)

        // 5. MessageStore timeline and ACK status update
        val store = org.nearby.mesh.domain.MessageStore()
        val initialMsg = org.nearby.mesh.domain.TextMessage(
            messageId = 12345L,
            senderNodeId = aliceNodeId,
            destNodeId = bobNodeId,
            senderDisplayName = "Alice",
            content = "Hello Bob!",
            timestampMs = now,
            isOutgoing = true,
            deliveryStatus = org.nearby.mesh.domain.DeliveryStatus.SENT,
            isEncrypted = true,
            hopCount = 0
        )
        store.addMessage(initialMsg)
        assertEquals(org.nearby.mesh.domain.DeliveryStatus.SENT, store.getTimeline(bobNodeId).value[0].deliveryStatus)

        // Simulate receiving ACK
        store.updateDeliveryStatus(bobNodeId, 12345L, org.nearby.mesh.domain.DeliveryStatus.DELIVERED)
        assertEquals(org.nearby.mesh.domain.DeliveryStatus.DELIVERED, store.getTimeline(bobNodeId).value[0].deliveryStatus)
    }
}
