package org.nearby.mesh.wifi

import org.nearby.mesh.domain.FileChunk
import org.nearby.mesh.domain.FileTransferConstants
import org.nearby.mesh.domain.HashUtils
import org.nearby.mesh.domain.SocketChunkCodec
import org.nearby.mesh.domain.TransferAccept
import org.nearby.mesh.domain.TransferAcceptCodec
import org.nearby.mesh.domain.TransferDirection
import org.nearby.mesh.domain.TransferOffer
import org.nearby.mesh.domain.TransferOfferCodec
import org.nearby.mesh.domain.TransferProgress
import org.nearby.mesh.domain.TransferStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

class FileChunkingTest {

    @Test
    fun fileTransferConstants_adheresToPrdCaps() {
        assertEquals(26_214_400L, FileTransferConstants.MAX_FILE_SIZE_BYTES)
        assertEquals(25 * 1024 * 1024L, FileTransferConstants.MAX_FILE_SIZE_BYTES)
        assertEquals(16_384, FileTransferConstants.CHUNK_SIZE_BYTES)
        assertEquals(16 * 1024, FileTransferConstants.CHUNK_SIZE_BYTES)
    }

    @Test
    fun hashUtils_sha256_computesKnownDigest() {
        // SHA-256 of empty byte array
        val emptyDigestHex = HashUtils.sha256Hex(ByteArray(0))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", emptyDigestHex)

        // Hex roundtrip
        val testBytes = byteArrayOf(0x01, 0x02, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())
        val hex = HashUtils.bytesToHex(testBytes)
        val roundtrip = HashUtils.hexToBytes(hex)
        assertArrayEquals(testBytes, roundtrip)
    }

    @Test
    fun transferOfferCodec_roundtrip_preservesAllMetadata() {
        val offer = TransferOffer(
            transferId = "1234567890abcdef",
            fileName = "emergency_map.png",
            fileSizeBytes = 450_000L,
            sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            totalChunks = 28,
            isVoiceNote = false,
            voiceDurationMs = 0L
        )

        val encoded = TransferOfferCodec.encode(offer)
        val decoded = TransferOfferCodec.decode(encoded)

        assertNotNull(decoded)
        assertEquals(offer.transferId, decoded!!.transferId)
        assertEquals(offer.fileName, decoded.fileName)
        assertEquals(offer.fileSizeBytes, decoded.fileSizeBytes)
        assertEquals(offer.sha256, decoded.sha256)
        assertEquals(offer.totalChunks, decoded.totalChunks)
        assertEquals(offer.isVoiceNote, decoded.isVoiceNote)
        assertEquals(offer.voiceDurationMs, decoded.voiceDurationMs)
    }

    @Test
    fun transferOfferCodec_voiceNote_preservesDuration() {
        val voiceOffer = TransferOffer(
            transferId = "fedcba0987654321",
            fileName = "voice_note_01.m4a",
            fileSizeBytes = 32_000L,
            sha256 = "11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff",
            totalChunks = 2,
            isVoiceNote = true,
            voiceDurationMs = 8_500L
        )

        val encoded = TransferOfferCodec.encode(voiceOffer)
        val decoded = TransferOfferCodec.decode(encoded)

        assertNotNull(decoded)
        assertTrue(decoded!!.isVoiceNote)
        assertEquals(8_500L, decoded.voiceDurationMs)
        assertEquals("voice_note_01.m4a", decoded.fileName)
    }

    @Test
    fun transferAcceptCodec_roundtrip_preservesAllFields() {
        val accept = TransferAccept(
            transferId = "abcdef1234567890",
            accepted = true,
            isGroupOwner = true,
            groupOwnerIp = "192.168.49.1",
            port = 8888
        )

        val encoded = TransferAcceptCodec.encode(accept)
        val decoded = TransferAcceptCodec.decode(encoded)

        assertNotNull(decoded)
        assertEquals(accept.transferId, decoded!!.transferId)
        assertTrue(decoded.accepted)
        assertTrue(decoded.isGroupOwner)
        assertEquals("192.168.49.1", decoded.groupOwnerIp)
        assertEquals(8888, decoded.port)
    }

    @Test
    fun socketChunkCodec_writeAndReadChunk_preservesFullPayload() {
        val random = SecureRandom()
        val rawData = ByteArray(FileTransferConstants.CHUNK_SIZE_BYTES)
        random.nextBytes(rawData)
        val chunkHash = HashUtils.sha256(rawData)

        val originalChunk = FileChunk(
            transferId = "transfer_test_99",
            chunkIndex = 5,
            totalChunks = 20,
            chunkSha256 = chunkHash,
            data = rawData
        )

        val outputStream = ByteArrayOutputStream()
        SocketChunkCodec.writeChunk(outputStream, originalChunk)

        val inputStream = ByteArrayInputStream(outputStream.toByteArray())
        val readChunk = SocketChunkCodec.readChunk(inputStream)

        assertNotNull(readChunk)
        assertEquals(originalChunk.transferId, readChunk!!.transferId)
        assertEquals(5, readChunk.chunkIndex)
        assertEquals(20, readChunk.totalChunks)
        assertArrayEquals(originalChunk.chunkSha256, readChunk.chunkSha256)
        assertArrayEquals(originalChunk.data, readChunk.data)
    }

    @Test
    fun socketChunkCodec_invalidMagic_returnsNull() {
        val corruptStream = ByteArrayInputStream(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04))
        val chunk = SocketChunkCodec.readChunk(corruptStream)
        assertNull(chunk)
    }

    @Test
    fun transferProgress_lifecycleProgression() {
        val progress = TransferProgress(
            transferId = "tx123",
            peerNodeId = "aabbccddeeff0011",
            fileName = "guide.pdf",
            fileSizeBytes = 100_000L,
            bytesTransferred = 50_000L,
            totalChunks = 10,
            chunksTransferred = 5,
            sha256Hash = "hash123",
            direction = TransferDirection.OUTGOING,
            status = TransferStatus.TRANSFERRING
        )

        assertEquals(0.5f, progress.progressFraction, 0.001f)
        assertFalse(progress.isFinished)

        val completed = progress.copy(
            bytesTransferred = 100_000L,
            chunksTransferred = 10,
            status = TransferStatus.COMPLETED
        )
        assertEquals(1.0f, completed.progressFraction, 0.001f)
        assertTrue(completed.isFinished)

        val failed = progress.copy(status = TransferStatus.FAILED, errorMessage = "Socket timeout")
        assertTrue(failed.isFinished)

        val cancelled = progress.copy(status = TransferStatus.CANCELLED)
        assertTrue(cancelled.isFinished)
    }
}
