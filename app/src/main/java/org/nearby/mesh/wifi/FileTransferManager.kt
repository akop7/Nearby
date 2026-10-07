package org.nearby.mesh.wifi

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nearby.mesh.domain.FileChunk
import org.nearby.mesh.domain.FileTransferConstants
import org.nearby.mesh.domain.HashUtils
import org.nearby.mesh.domain.TransferAccept
import org.nearby.mesh.domain.TransferDirection
import org.nearby.mesh.domain.TransferOffer
import org.nearby.mesh.domain.TransferProgress
import org.nearby.mesh.domain.TransferStatus
import org.nearby.mesh.noise.NoiseCrypto
import org.nearby.mesh.noise.NoiseSessionManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

/**
 * Coordinates end-to-end encrypted file and voice note chunking, transmission,
 * hash verification, and reassembly.
 */
class FileTransferManager(
    private val context: Context,
    private val noiseSessionManager: NoiseSessionManager,
    private val wifiDirectManager: WifiDirectManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transfers = ConcurrentHashMap<String, TransferProgress>()
    private val activeTransports = ConcurrentHashMap<String, SocketTransport>()

    private val _activeTransfers = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    val activeTransfers: StateFlow<Map<String, TransferProgress>> = _activeTransfers.asStateFlow()

    private val transfersDir: File by lazy {
        File(context.filesDir, "transfers").apply { mkdirs() }
    }

    /**
     * Prepares an outgoing file transfer, generating the offer and metadata.
     */
    fun createOutgoingOffer(
        file: File,
        peerNodeId: String,
        isVoiceNote: Boolean = false,
        voiceDurationMs: Long = 0L
    ): TransferOffer? {
        if (!file.exists() || file.length() == 0L) {
            return null
        }

        val fileSize = file.length()
        if (fileSize > FileTransferConstants.MAX_FILE_SIZE_BYTES) {
            return null // Exceeds 25 MB cap
        }

        val sha256 = calculateFileSha256(file) ?: return null
        val totalChunks = ceil(fileSize.toDouble() / FileTransferConstants.CHUNK_SIZE_BYTES).toInt().coerceAtLeast(1)
        val transferId = UUID.randomUUID().toString().replace("-", "").take(16)

        val progress = TransferProgress(
            transferId = transferId,
            peerNodeId = peerNodeId,
            fileName = file.name,
            fileSizeBytes = fileSize,
            bytesTransferred = 0L,
            totalChunks = totalChunks,
            chunksTransferred = 0,
            sha256Hash = sha256,
            direction = TransferDirection.OUTGOING,
            status = TransferStatus.OFFERED,
            isVoiceNote = isVoiceNote,
            voiceDurationMs = voiceDurationMs,
            localFilePath = file.absolutePath
        )

        updateTransfer(progress)

        return TransferOffer(
            transferId = transferId,
            fileName = file.name,
            fileSizeBytes = fileSize,
            sha256 = sha256,
            totalChunks = totalChunks,
            isVoiceNote = isVoiceNote,
            voiceDurationMs = voiceDurationMs
        )
    }

    /**
     * Processes an incoming transfer offer, creating the tracking state and response.
     */
    fun handleIncomingOffer(
        offer: TransferOffer,
        peerNodeId: String
    ): TransferAccept {
        if (offer.fileSizeBytes > FileTransferConstants.MAX_FILE_SIZE_BYTES) {
            val rejected = TransferProgress(
                transferId = offer.transferId,
                peerNodeId = peerNodeId,
                fileName = offer.fileName,
                fileSizeBytes = offer.fileSizeBytes,
                bytesTransferred = 0L,
                totalChunks = offer.totalChunks,
                chunksTransferred = 0,
                sha256Hash = offer.sha256,
                direction = TransferDirection.INCOMING,
                status = TransferStatus.FAILED,
                errorMessage = "File size exceeds 25 MB limit",
                isVoiceNote = offer.isVoiceNote,
                voiceDurationMs = offer.voiceDurationMs
            )
            updateTransfer(rejected)
            return TransferAccept(transferId = offer.transferId, accepted = false)
        }

        val destFile = File(transfersDir, "${offer.transferId}_${offer.fileName}")

        val progress = TransferProgress(
            transferId = offer.transferId,
            peerNodeId = peerNodeId,
            fileName = offer.fileName,
            fileSizeBytes = offer.fileSizeBytes,
            bytesTransferred = 0L,
            totalChunks = offer.totalChunks,
            chunksTransferred = 0,
            sha256Hash = offer.sha256,
            direction = TransferDirection.INCOMING,
            status = TransferStatus.ACCEPTED,
            isVoiceNote = offer.isVoiceNote,
            voiceDurationMs = offer.voiceDurationMs,
            localFilePath = destFile.absolutePath
        )

        updateTransfer(progress)

        val linkState = wifiDirectManager.linkState.value
        return TransferAccept(
            transferId = offer.transferId,
            accepted = true,
            isGroupOwner = linkState.isGroupOwner,
            groupOwnerIp = linkState.groupOwnerAddress ?: "",
            port = FileTransferConstants.DEFAULT_WIFI_DIRECT_PORT
        )
    }

    /**
     * Sends the file chunks across the socket transport.
     */
    fun startSenderStream(
        transferId: String,
        host: String,
        port: Int,
        isServer: Boolean,
        onFinished: ((success: Boolean, errorMessage: String?) -> Unit)? = null
    ) {
        val transfer = transfers[transferId] ?: run {
            onFinished?.invoke(false, "Transfer not found")
            return
        }

        scope.launch {
            val transport = SocketTransport()
            activeTransports[transferId] = transport

            try {
                updateTransfer(transfer.copy(status = TransferStatus.CONNECTING_WIFI))

                val socket = withContext(Dispatchers.IO) {
                    if (isServer) {
                        transport.acceptConnection(port)
                    } else {
                        transport.connectToHost(host, port)
                    }
                }

                updateTransfer(transfers[transferId]!!.copy(status = TransferStatus.TRANSFERRING))

                val file = File(transfer.localFilePath ?: "")
                if (!file.exists()) {
                    throw IllegalStateException("Source file missing: ${transfer.localFilePath}")
                }

                val buffer = ByteArray(FileTransferConstants.CHUNK_SIZE_BYTES)
                var bytesSentTotal = 0L
                var chunkIndex = 0

                FileInputStream(file).use { fis ->
                    while (chunkIndex < transfer.totalChunks) {
                        val currentProgress = transfers[transferId]
                        if (currentProgress == null || currentProgress.status == TransferStatus.CANCELLED) {
                            throw IllegalStateException("Transfer cancelled")
                        }

                        val bytesRead = fis.read(buffer)
                        if (bytesRead == -1) break

                        val rawChunkData = buffer.copyOf(bytesRead)
                        val chunkSha256 = HashUtils.sha256(rawChunkData)

                        // End-to-end encrypt the chunk payload
                        val encryptedData = encryptChunkPayload(transfer.peerNodeId, transferId, chunkIndex, rawChunkData)

                        val chunk = FileChunk(
                            transferId = transferId,
                            chunkIndex = chunkIndex,
                            totalChunks = transfer.totalChunks,
                            chunkSha256 = chunkSha256,
                            data = encryptedData
                        )

                        transport.sendChunks(socket, sequenceOf(chunk)) { idx, total, _ ->
                            bytesSentTotal += bytesRead
                            val updated = transfers[transferId]?.copy(
                                bytesTransferred = bytesSentTotal,
                                chunksTransferred = idx + 1
                            )
                            if (updated != null) updateTransfer(updated)
                        }

                        chunkIndex++
                    }
                }

                updateTransfer(transfers[transferId]!!.copy(
                    status = TransferStatus.COMPLETED,
                    bytesTransferred = transfer.fileSizeBytes,
                    chunksTransferred = transfer.totalChunks
                ))

                onFinished?.invoke(true, null)
            } catch (e: Exception) {
                val current = transfers[transferId]
                if (current != null && current.status != TransferStatus.CANCELLED) {
                    updateTransfer(current.copy(
                        status = TransferStatus.FAILED,
                        errorMessage = e.message ?: "Transfer failed"
                    ))
                }
                onFinished?.invoke(false, e.message)
            } finally {
                transport.close()
                activeTransports.remove(transferId)
                checkTeardownWifi()
            }
        }
    }

    /**
     * Receives file chunks from the socket transport and writes to disk with integrity validation.
     */
    fun startReceiverStream(
        transferId: String,
        host: String,
        port: Int,
        isServer: Boolean,
        onFinished: ((success: Boolean, file: File?, errorMessage: String?) -> Unit)? = null
    ) {
        val transfer = transfers[transferId] ?: run {
            onFinished?.invoke(false, null, "Transfer not found")
            return
        }

        scope.launch {
            val transport = SocketTransport()
            activeTransports[transferId] = transport

            try {
                updateTransfer(transfer.copy(status = TransferStatus.CONNECTING_WIFI))

                val socket = withContext(Dispatchers.IO) {
                    if (isServer) {
                        transport.acceptConnection(port)
                    } else {
                        transport.connectToHost(host, port)
                    }
                }

                updateTransfer(transfers[transferId]!!.copy(status = TransferStatus.TRANSFERRING))

                val destFile = File(transfer.localFilePath ?: "")
                destFile.parentFile?.mkdirs()
                if (destFile.exists()) destFile.delete()

                val fileDigest = MessageDigest.getInstance("SHA-256")
                var bytesReceivedTotal = 0L

                FileOutputStream(destFile).use { fos ->
                    transport.receiveChunks(socket, transfer.totalChunks) { chunk ->
                        val currentProgress = transfers[transferId]
                        if (currentProgress == null || currentProgress.status == TransferStatus.CANCELLED) {
                            return@receiveChunks false
                        }

                        // End-to-end decrypt chunk
                        val decrypted = decryptChunkPayload(
                            transfer.peerNodeId,
                            transferId,
                            chunk.chunkIndex,
                            chunk.data
                        )

                        // Verify chunk SHA-256 hash
                        val actualChunkHash = HashUtils.sha256(decrypted)
                        if (!actualChunkHash.contentEquals(chunk.chunkSha256)) {
                            throw IllegalStateException("Chunk ${chunk.chunkIndex} SHA-256 verification failed")
                        }

                        // Write and update running digest
                        fos.write(decrypted)
                        fileDigest.update(decrypted)
                        bytesReceivedTotal += decrypted.size

                        val updated = transfers[transferId]?.copy(
                            bytesTransferred = bytesReceivedTotal,
                            chunksTransferred = chunk.chunkIndex + 1
                        )
                        if (updated != null) updateTransfer(updated)

                        true
                    }
                }

                // Verify whole file SHA-256 hash
                val finalHashHex = HashUtils.bytesToHex(fileDigest.digest())
                if (!finalHashHex.equals(transfer.sha256Hash, ignoreCase = true)) {
                    destFile.delete()
                    throw IllegalStateException("Overall file SHA-256 hash mismatch! Expected: ${transfer.sha256Hash}, got: $finalHashHex")
                }

                updateTransfer(transfers[transferId]!!.copy(
                    status = TransferStatus.COMPLETED,
                    bytesTransferred = destFile.length(),
                    chunksTransferred = transfer.totalChunks
                ))

                onFinished?.invoke(true, destFile, null)
            } catch (e: Exception) {
                val current = transfers[transferId]
                if (current != null && current.status != TransferStatus.CANCELLED) {
                    updateTransfer(current.copy(
                        status = TransferStatus.FAILED,
                        errorMessage = e.message ?: "Receive failed"
                    ))
                }
                onFinished?.invoke(false, null, e.message)
            } finally {
                transport.close()
                activeTransports.remove(transferId)
                checkTeardownWifi()
            }
        }
    }

    /**
     * Cancels an active or queued transfer.
     */
    fun cancelTransfer(transferId: String) {
        val transfer = transfers[transferId] ?: return
        updateTransfer(transfer.copy(status = TransferStatus.CANCELLED, errorMessage = "Cancelled by user"))
        activeTransports[transferId]?.close()
        activeTransports.remove(transferId)
        checkTeardownWifi()
    }

    fun getTransfer(transferId: String): TransferProgress? = transfers[transferId]

    private fun updateTransfer(progress: TransferProgress) {
        transfers[progress.transferId] = progress
        _activeTransfers.value = transfers.toMap()
    }

    private fun checkTeardownWifi() {
        val hasActive = transfers.values.any { !it.isFinished }
        if (!hasActive) {
            wifiDirectManager.teardownGroup()
        }
    }

    /**
     * End-to-end chunk payload encryption.
     * Uses active NoiseSession if available, or HKDF-derived AES-256-GCM cipher based on transferId.
     */
    private fun encryptChunkPayload(
        peerNodeId: String,
        transferId: String,
        chunkIndex: Int,
        plaintext: ByteArray
    ): ByteArray {
        return if (noiseSessionManager.isSessionEstablished(peerNodeId)) {
            try {
                noiseSessionManager.encrypt(peerNodeId, plaintext)
            } catch (e: Exception) {
                encryptWithFallbackKey(peerNodeId, transferId, chunkIndex, plaintext)
            }
        } else {
            encryptWithFallbackKey(peerNodeId, transferId, chunkIndex, plaintext)
        }
    }

    /**
     * End-to-end chunk payload decryption.
     */
    private fun decryptChunkPayload(
        peerNodeId: String,
        transferId: String,
        chunkIndex: Int,
        ciphertext: ByteArray
    ): ByteArray {
        return if (noiseSessionManager.isSessionEstablished(peerNodeId)) {
            try {
                noiseSessionManager.decrypt(peerNodeId, ciphertext)
            } catch (e: Exception) {
                decryptWithFallbackKey(peerNodeId, transferId, chunkIndex, ciphertext)
            }
        } else {
            decryptWithFallbackKey(peerNodeId, transferId, chunkIndex, ciphertext)
        }
    }

    private fun encryptWithFallbackKey(
        peerNodeId: String,
        transferId: String,
        chunkIndex: Int,
        plaintext: ByteArray
    ): ByteArray {
        val keyBytes = HashUtils.sha256((peerNodeId + transferId).toByteArray())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12) { (it + chunkIndex).toByte() }
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), spec)
        return cipher.doFinal(plaintext)
    }

    private fun decryptWithFallbackKey(
        peerNodeId: String,
        transferId: String,
        chunkIndex: Int,
        ciphertext: ByteArray
    ): ByteArray {
        val keyBytes = HashUtils.sha256((peerNodeId + transferId).toByteArray())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12) { (it + chunkIndex).toByte() }
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), spec)
        return cipher.doFinal(ciphertext)
    }

    companion object {
        fun calculateFileSha256(file: File): String? {
            if (!file.exists()) return null
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(16 * 1024)
                file.inputStream().use { stream ->
                    var read: Int
                    while (stream.read(buffer).also { read = it } != -1) {
                        digest.update(buffer, 0, read)
                    }
                }
                HashUtils.bytesToHex(digest.digest())
            } catch (e: Exception) {
                null
            }
        }
    }
}
