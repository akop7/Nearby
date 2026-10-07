package org.nearby.mesh.domain

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * File size and chunk constraints defined in PRD §5.4.
 */
object FileTransferConstants {
    const val MAX_FILE_SIZE_BYTES: Long = 25 * 1024 * 1024L // 25 MB cap
    const val CHUNK_SIZE_BYTES: Int = 16 * 1024 // 16 KB chunks
    const val DEFAULT_WIFI_DIRECT_PORT: Int = 8888
}

/**
 * Transfer direction relative to the local device.
 */
enum class TransferDirection {
    OUTGOING,
    INCOMING
}

/**
 * Status lifecycle of a file/media transfer.
 */
enum class TransferStatus {
    OFFERED,
    ACCEPTED,
    CONNECTING_WIFI,
    TRANSFERRING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Real-time progress and state of an active or finished transfer.
 */
data class TransferProgress(
    val transferId: String,
    val peerNodeId: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val bytesTransferred: Long,
    val totalChunks: Int,
    val chunksTransferred: Int,
    val sha256Hash: String,
    val direction: TransferDirection,
    val status: TransferStatus,
    val errorMessage: String? = null,
    val isVoiceNote: Boolean = false,
    val voiceDurationMs: Long = 0L,
    val localFilePath: String? = null
) {
    val progressFraction: Float
        get() = if (fileSizeBytes > 0) {
            (bytesTransferred.toFloat() / fileSizeBytes).coerceIn(0f, 1f)
        } else 0f

    val isFinished: Boolean
        get() = status == TransferStatus.COMPLETED ||
                status == TransferStatus.FAILED ||
                status == TransferStatus.CANCELLED
}

/**
 * Negotiation offer sent via BLE before upgrading to Wi-Fi Direct.
 */
data class TransferOffer(
    val transferId: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val sha256: String,
    val totalChunks: Int,
    val isVoiceNote: Boolean = false,
    val voiceDurationMs: Long = 0L
)

/**
 * Response accepting or rejecting a transfer offer via BLE.
 */
data class TransferAccept(
    val transferId: String,
    val accepted: Boolean,
    val isGroupOwner: Boolean = false,
    val groupOwnerIp: String = "",
    val port: Int = FileTransferConstants.DEFAULT_WIFI_DIRECT_PORT
)

/**
 * A single raw or encrypted chunk of a file.
 */
data class FileChunk(
    val transferId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val chunkSha256: ByteArray,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FileChunk
        if (transferId != other.transferId) return false
        if (chunkIndex != other.chunkIndex) return false
        if (totalChunks != other.totalChunks) return false
        if (!chunkSha256.contentEquals(other.chunkSha256)) return false
        if (!data.contentEquals(other.data)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = transferId.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + totalChunks
        result = 31 * result + chunkSha256.contentHashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * Utility functions for SHA-256 calculation and verification.
 */
object HashUtils {
    fun sha256(bytes: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes)
    }

    fun sha256Hex(bytes: ByteArray): String {
        return bytesToHex(sha256(bytes))
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val result = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            val high = Character.digit(hex[i], 16)
            val low = Character.digit(hex[i + 1], 16)
            result[i / 2] = ((high shl 4) or low).toByte()
        }
        return result
    }
}

/**
 * Compact binary encoder/decoder for [TransferOffer] over BLE packets.
 */
object TransferOfferCodec {
    private const val MAGIC_0: Byte = 0x54 // 'T'
    private const val MAGIC_1: Byte = 0x4F // 'O'

    fun encode(offer: TransferOffer): ByteArray {
        val nameBytes = offer.fileName.toByteArray(Charsets.UTF_8).take(64).toByteArray()
        val idBytes = offer.transferId.toByteArray(Charsets.UTF_8).take(32).toByteArray()
        val hashBytes = if (offer.sha256.length == 64) HashUtils.hexToBytes(offer.sha256) else ByteArray(32)

        val totalSize = 2 + 1 + idBytes.size + 8 + 4 + 1 + 8 + 32 + 1 + nameBytes.size
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.order(ByteOrder.BIG_ENDIAN)

        buffer.put(MAGIC_0)
        buffer.put(MAGIC_1)

        buffer.put(idBytes.size.toByte())
        buffer.put(idBytes)

        buffer.putLong(offer.fileSizeBytes)
        buffer.putInt(offer.totalChunks)
        buffer.put(if (offer.isVoiceNote) 1.toByte() else 0.toByte())
        buffer.putLong(offer.voiceDurationMs)
        buffer.put(hashBytes)

        buffer.put(nameBytes.size.toByte())
        buffer.put(nameBytes)

        return buffer.array()
    }

    fun decode(bytes: ByteArray): TransferOffer? {
        if (bytes.size < 2 + 1 + 8 + 4 + 1 + 8 + 32 + 1) return null

        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.BIG_ENDIAN)

        val m0 = buffer.get()
        val m1 = buffer.get()
        if (m0 != MAGIC_0 || m1 != MAGIC_1) return null

        val idLen = buffer.get().toInt() and 0xFF
        if (buffer.remaining() < idLen) return null
        val idBytes = ByteArray(idLen)
        buffer.get(idBytes)
        val transferId = String(idBytes, Charsets.UTF_8)

        if (buffer.remaining() < 8 + 4 + 1 + 8 + 32 + 1) return null
        val fileSize = buffer.getLong()
        val totalChunks = buffer.getInt()
        val isVoice = buffer.get().toInt() == 1
        val durationMs = buffer.getLong()

        val hashBytes = ByteArray(32)
        buffer.get(hashBytes)
        val sha256 = HashUtils.bytesToHex(hashBytes)

        val nameLen = buffer.get().toInt() and 0xFF
        if (buffer.remaining() < nameLen) return null
        val nameBytes = ByteArray(nameLen)
        buffer.get(nameBytes)
        val fileName = String(nameBytes, Charsets.UTF_8)

        return TransferOffer(
            transferId = transferId,
            fileName = fileName,
            fileSizeBytes = fileSize,
            sha256 = sha256,
            totalChunks = totalChunks,
            isVoiceNote = isVoice,
            voiceDurationMs = durationMs
        )
    }
}

/**
 * Compact binary encoder/decoder for [TransferAccept] over BLE packets.
 */
object TransferAcceptCodec {
    private const val MAGIC_0: Byte = 0x54 // 'T'
    private const val MAGIC_1: Byte = 0x41 // 'A'

    fun encode(accept: TransferAccept): ByteArray {
        val idBytes = accept.transferId.toByteArray(Charsets.UTF_8).take(32).toByteArray()
        val ipBytes = accept.groupOwnerIp.toByteArray(Charsets.UTF_8).take(32).toByteArray()

        val totalSize = 2 + 1 + idBytes.size + 1 + 1 + 4 + 1 + ipBytes.size
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.order(ByteOrder.BIG_ENDIAN)

        buffer.put(MAGIC_0)
        buffer.put(MAGIC_1)

        buffer.put(idBytes.size.toByte())
        buffer.put(idBytes)

        buffer.put(if (accept.accepted) 1.toByte() else 0.toByte())
        buffer.put(if (accept.isGroupOwner) 1.toByte() else 0.toByte())
        buffer.putInt(accept.port)

        buffer.put(ipBytes.size.toByte())
        buffer.put(ipBytes)

        return buffer.array()
    }

    fun decode(bytes: ByteArray): TransferAccept? {
        if (bytes.size < 2 + 1 + 1 + 1 + 4 + 1) return null

        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.BIG_ENDIAN)

        val m0 = buffer.get()
        val m1 = buffer.get()
        if (m0 != MAGIC_0 || m1 != MAGIC_1) return null

        val idLen = buffer.get().toInt() and 0xFF
        if (buffer.remaining() < idLen) return null
        val idBytes = ByteArray(idLen)
        buffer.get(idBytes)
        val transferId = String(idBytes, Charsets.UTF_8)

        if (buffer.remaining() < 1 + 1 + 4 + 1) return null
        val accepted = buffer.get().toInt() == 1
        val isGroupOwner = buffer.get().toInt() == 1
        val port = buffer.getInt()

        val ipLen = buffer.get().toInt() and 0xFF
        if (buffer.remaining() < ipLen) return null
        val ipBytes = ByteArray(ipLen)
        buffer.get(ipBytes)
        val groupOwnerIp = String(ipBytes, Charsets.UTF_8)

        return TransferAccept(
            transferId = transferId,
            accepted = accepted,
            isGroupOwner = isGroupOwner,
            groupOwnerIp = groupOwnerIp,
            port = port
        )
    }
}

/**
 * Socket frame protocol for streaming encrypted file chunks over high-speed TCP sockets.
 *
 * Frame structure:
 *  [Magic 4B: 'NFCH'] [TransferIdLen 1B] [TransferId XB]
 *  [ChunkIndex 4B] [TotalChunks 4B] [ChunkSha256 32B]
 *  [PayloadLength 4B] [EncryptedPayload bytes...]
 */
object SocketChunkCodec {
    private val MAGIC = byteArrayOf(0x4E, 0x46, 0x43, 0x48) // 'N', 'F', 'C', 'H'

    fun writeChunk(outputStream: OutputStream, chunk: FileChunk) {
        val idBytes = chunk.transferId.toByteArray(Charsets.UTF_8).take(64).toByteArray()
        val headerSize = 4 + 1 + idBytes.size + 4 + 4 + 32 + 4
        val buffer = ByteBuffer.allocate(headerSize)
        buffer.order(ByteOrder.BIG_ENDIAN)

        buffer.put(MAGIC)
        buffer.put(idBytes.size.toByte())
        buffer.put(idBytes)
        buffer.putInt(chunk.chunkIndex)
        buffer.putInt(chunk.totalChunks)
        buffer.put(chunk.chunkSha256)
        buffer.putInt(chunk.data.size)

        outputStream.write(buffer.array())
        outputStream.write(chunk.data)
        outputStream.flush()
    }

    fun readChunk(inputStream: InputStream): FileChunk? {
        val magicBuffer = ByteArray(4)
        if (!readFully(inputStream, magicBuffer)) return null
        if (!magicBuffer.contentEquals(MAGIC)) return null

        val idLenByte = inputStream.read()
        if (idLenByte == -1) return null
        val idLen = idLenByte and 0xFF

        val idBytes = ByteArray(idLen)
        if (!readFully(inputStream, idBytes)) return null
        val transferId = String(idBytes, Charsets.UTF_8)

        val headerRemaining = ByteArray(4 + 4 + 32 + 4)
        if (!readFully(inputStream, headerRemaining)) return null

        val buffer = ByteBuffer.wrap(headerRemaining)
        buffer.order(ByteOrder.BIG_ENDIAN)

        val chunkIndex = buffer.getInt()
        val totalChunks = buffer.getInt()
        val chunkSha256 = ByteArray(32)
        buffer.get(chunkSha256)
        val payloadLen = buffer.getInt()

        if (payloadLen < 0 || payloadLen > FileTransferConstants.CHUNK_SIZE_BYTES + 1024) {
            return null
        }

        val payload = ByteArray(payloadLen)
        if (!readFully(inputStream, payload)) return null

        return FileChunk(
            transferId = transferId,
            chunkIndex = chunkIndex,
            totalChunks = totalChunks,
            chunkSha256 = chunkSha256,
            data = payload
        )
    }

    private fun readFully(inputStream: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val bytesRead = inputStream.read(buffer, offset, buffer.size - offset)
            if (bytesRead == -1) return false
            offset += bytesRead
        }
        return true
    }
}
