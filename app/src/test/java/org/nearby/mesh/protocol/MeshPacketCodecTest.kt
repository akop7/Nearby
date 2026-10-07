package org.nearby.mesh.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshPacketCodecTest {

    @Test
    fun encodeAndDecode_singleChunkPacket_roundTripsCorrectly() {
        val originalPayload = "Hello from Survivor-1234!".toByteArray(Charsets.UTF_8)
        val packet = MeshPacket(
            type = PacketType.TEXT_MESSAGE,
            ttl = 7,
            hopCount = 0,
            packetId = 12345678901234L,
            senderNodeId = "0123456789abcdef",
            destNodeId = "fedcba9876543210",
            chunkIndex = 0,
            chunkCount = 1,
            payload = originalPayload
        )

        val encoded = MeshPacketCodec.encode(packet)
        assertEquals(MeshPacket.HEADER_SIZE + originalPayload.size, encoded.size)

        val decoded = MeshPacketCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(packet, decoded)
    }

    @Test
    fun encodeAndDecode_emptyPayload_succeeds() {
        val packet = MeshPacket(
            type = PacketType.BEACON_PING,
            ttl = 3,
            hopCount = 1,
            packetId = 99999L,
            senderNodeId = "aaaaaaaaaaaaaaaa",
            destNodeId = MeshPacket.BROADCAST_NODE_ID,
            chunkIndex = 0,
            chunkCount = 1,
            payload = ByteArray(0)
        )

        val encoded = MeshPacketCodec.encode(packet)
        assertEquals(MeshPacket.HEADER_SIZE, encoded.size)

        val decoded = MeshPacketCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(packet, decoded)
    }

    @Test
    fun decode_corruptedMagic_returnsNull() {
        val packet = MeshPacket(
            type = PacketType.TEXT_MESSAGE,
            ttl = 5,
            hopCount = 0,
            packetId = 42L,
            senderNodeId = "1111222233334444",
            destNodeId = MeshPacket.BROADCAST_NODE_ID,
            payload = "Test".toByteArray()
        )

        val encoded = MeshPacketCodec.encode(packet)
        encoded[0] = 0x00 // Corrupt magic byte 0

        val decoded = MeshPacketCodec.decode(encoded)
        assertNull(decoded)
    }

    @Test
    fun forRelay_decrementsTtlAndIncrementsHopCount() {
        val packet = MeshPacket(
            type = PacketType.TEXT_MESSAGE,
            ttl = 5,
            hopCount = 1,
            packetId = 100L,
            senderNodeId = "0000111122223333",
            destNodeId = MeshPacket.BROADCAST_NODE_ID,
            payload = ByteArray(10)
        )

        val relayed1 = packet.forRelay()
        assertNotNull(relayed1)
        assertEquals(4.toByte(), relayed1!!.ttl)
        assertEquals(2.toByte(), relayed1.hopCount)

        val relayed2 = relayed1.forRelay()
        assertNotNull(relayed2)
        assertEquals(3.toByte(), relayed2!!.ttl)
        assertEquals(3.toByte(), relayed2.hopCount)
    }

    @Test
    fun forRelay_dropsPacketWhenTtlExhausted() {
        val dyingPacket = MeshPacket(
            type = PacketType.TEXT_MESSAGE,
            ttl = 1,
            hopCount = 6,
            packetId = 101L,
            senderNodeId = "0000111122223333",
            destNodeId = MeshPacket.BROADCAST_NODE_ID,
            payload = ByteArray(10)
        )

        val result = dyingPacket.forRelay()
        assertNull("Packet with TTL=1 should not be relayed further", result)
    }

    @Test
    fun messageChunkerAndReassembler_multiChunkPayload() {
        val largeData = ByteArray(400) { (it % 256).toByte() }
        val sender = "1234567890abcdef"
        val dest = "fedcba9876543210"

        val chunks = MessageChunker.chunkMessage(
            type = PacketType.TRANSFER_OFFER,
            ttl = 7,
            senderNodeId = sender,
            destNodeId = dest,
            payload = largeData,
            chunkSize = 150
        )

        assertEquals(3, chunks.size) // 150 + 150 + 100 = 400
        assertEquals(0.toShort(), chunks[0].chunkIndex)
        assertEquals(1.toShort(), chunks[1].chunkIndex)
        assertEquals(2.toShort(), chunks[2].chunkIndex)
        assertEquals(3.toShort(), chunks[0].chunkCount)

        val reassembler = PacketReassembler()

        // Deliver chunk 0
        assertNull(reassembler.addChunk(chunks[0]))
        // Deliver chunk 2 (out of order test)
        assertNull(reassembler.addChunk(chunks[2]))
        // Deliver chunk 1 -> full message arrives
        val assembled = reassembler.addChunk(chunks[1])
        assertNotNull(assembled)
        assertArrayEquals(largeData, assembled)
    }
}
