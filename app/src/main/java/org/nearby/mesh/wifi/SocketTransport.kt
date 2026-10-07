package org.nearby.mesh.wifi

import org.nearby.mesh.domain.FileChunk
import org.nearby.mesh.domain.SocketChunkCodec
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Socket transport providing direct TCP stream communication for high-bandwidth chunk transfer.
 */
class SocketTransport {

    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null

    /**
     * Starts listening as a TCP server on [port] and blocks until a peer connects or timeout occurs.
     */
    @Throws(IOException::class)
    fun acceptConnection(port: Int, timeoutMs: Int = 30_000): Socket {
        close()
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        ss.soTimeout = timeoutMs
        serverSocket = ss

        val client = ss.accept()
        activeSocket = client
        return client
    }

    /**
     * Connects as a TCP client to the peer at [host]:[port].
     */
    @Throws(IOException::class)
    fun connectToHost(host: String, port: Int, timeoutMs: Int = 15_000): Socket {
        close()
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        activeSocket = socket
        return socket
    }

    /**
     * Sends a stream of chunks through the connected socket.
     */
    @Throws(IOException::class)
    fun sendChunks(
        socket: Socket,
        chunks: Sequence<FileChunk>,
        onChunkSent: (chunkIndex: Int, totalChunks: Int, bytesSent: Int) -> Unit
    ) {
        val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        for (chunk in chunks) {
            SocketChunkCodec.writeChunk(out, chunk)
            onChunkSent(chunk.chunkIndex, chunk.totalChunks, chunk.data.size)
        }
        out.flush()
    }

    /**
     * Receives chunks continuously from the connected socket until all [expectedTotalChunks] arrive.
     */
    @Throws(IOException::class)
    fun receiveChunks(
        socket: Socket,
        expectedTotalChunks: Int,
        onChunkReceived: (chunk: FileChunk) -> Boolean
    ) {
        val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
        var receivedCount = 0

        while (receivedCount < expectedTotalChunks) {
            val chunk = SocketChunkCodec.readChunk(input) ?: break
            receivedCount++
            val shouldContinue = onChunkReceived(chunk)
            if (!shouldContinue || chunk.chunkIndex == chunk.totalChunks - 1) {
                break
            }
        }
    }

    /**
     * Closes any active server socket and peer connection.
     */
    @Synchronized
    fun close() {
        try {
            activeSocket?.close()
        } catch (ignored: Exception) {}
        activeSocket = null

        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
        serverSocket = null
    }
}
