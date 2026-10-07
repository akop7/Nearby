package org.nearby.mesh.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.nearby.mesh.protocol.MeshPacket
import org.nearby.mesh.protocol.MeshPacketCodec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet

/**
 * BLE GATT Transport Layer for Noise_XX handshake exchange and persistent message delivery.
 *
 * Implements a reliable client/server pipeline:
 * - Deterministic role assignment via nodeId lexicographic comparison.
 * - Robust Android 13+ (API 33+) backward/forward compatible GATT callbacks and operations.
 * - Unified outbound queue draining over live GATT connections for text messages, SOS alerts, and ACKs.
 * - Automatic packet identification via binary magic bytes ('NB') preventing state collisions.
 */
@SuppressLint("MissingPermission")
class BleGattTransport(
    private val context: Context,
    private val engine: BleMeshEngine
) {
    companion object {
        private const val TAG = "BleGattTransport"

        // Mesh GATT Service UUID (same as advertising UUID)
        val SERVICE_UUID: UUID = BleMeshEngine.SERVICE_UUID

        // Characteristic for sending data TO the server (client writes here)
        val CHAR_TX_UUID: UUID = UUID.fromString("0000fe27-0000-1000-8000-00805f9b34fb")

        // Characteristic for receiving data FROM the server (server notifies here)
        val CHAR_RX_UUID: UUID = UUID.fromString("0000fe28-0000-1000-8000-00805f9b34fb")

        // Standard CCCD UUID for enabling notifications
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val HANDSHAKE_TIMEOUT_MS = 4000L
        private const val MTU_SIZE = 512
        private const val MAX_CHAR_VALUE = 509 // MTU - 3 bytes ATT header
        private const val DATA_DRAIN_INTERVAL_MS = 30L  // 30ms between data packet polls
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // GATT Server
    private var gattServer: BluetoothGattServer? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    // Active GATT client connections: peerNodeId -> BluetoothGatt
    private val clientConnections = ConcurrentHashMap<String, BluetoothGatt>()

    // Connected server-side devices: MAC address -> nodeId (populated when we recognize them)
    private val serverSideDevices = ConcurrentHashMap<String, String>()

    // Timeout jobs per peer
    private val timeoutJobs = ConcurrentHashMap<String, Job>()

    // Peers that are actively connecting or in handshake (to avoid duplicate connect attempts)
    private val activeHandshakePeers = ConcurrentHashMap<String, Boolean>()

    // Peers whose handshake is COMPLETE and the connection is being used for data delivery
    private val connectedDataPeers = ConcurrentSkipListSet<String>()

    // Server-side: map from peerNodeId -> connected BluetoothDevice (for notifying back)
    private val serverSideConnectedDevices = ConcurrentHashMap<String, BluetoothDevice>()

    // Pending write continuations for GATT client serialized write queue
    private val pendingWrites = ConcurrentHashMap<String, ArrayDeque<ByteArray>>()
    private val writeInProgress = ConcurrentHashMap<String, Boolean>()

    // Single global outbound queue drainer job
    private var globalDrainJob: Job? = null

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    fun start() {
        startGattServer()
        ensureDataDrainerStarted()
    }

    fun stop() {
        globalDrainJob?.cancel()
        globalDrainJob = null

        timeoutJobs.values.forEach { it.cancel() }
        timeoutJobs.clear()

        clientConnections.values.forEach { gatt ->
            try { gatt.disconnect(); gatt.close() } catch (_: Exception) {}
        }
        clientConnections.clear()
        activeHandshakePeers.clear()
        connectedDataPeers.clear()
        serverSideConnectedDevices.clear()
        serverSideDevices.clear()
        pendingWrites.clear()
        writeInProgress.clear()

        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        rxCharacteristic = null
    }

    // ─────────────────────────────────────────────────────────────────────
    // Post-handshake data delivery
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Enqueues a serialized [MeshPacket] for delivery to [peerNodeId] over the live GATT connection.
     */
    fun sendDataPacket(peerNodeId: String, packetBytes: ByteArray) {
        val normId = peerNodeId.lowercase()
        val gatt = clientConnections[normId]
        if (gatt != null) {
            // We are GATT client: write to server's TX characteristic.
            // Prefix with 8-byte localNodeId for consistent server routing.
            val nodeIdBytes = hexToBytes(engine.router.localNodeId)
            val frame = nodeIdBytes + packetBytes
            enqueueWrite(gatt, normId, frame)
        } else {
            // We are GATT server: notify the connected client via RX characteristic.
            val device = serverSideConnectedDevices[normId] ?: run {
                Log.w(TAG, "sendDataPacket: no server-side device connection for peer $normId")
                return
            }
            notifyDataToClient(device, normId, packetBytes)
        }
    }

    /**
     * Continuously drains [engine.router.outboundQueue] and routes packets over GATT links.
     */
    @Synchronized
    fun ensureDataDrainerStarted() {
        if (globalDrainJob?.isActive == true) return
        globalDrainJob = scope.launch {
            Log.d(TAG, "Global outbound data drainer started")
            while (isActive) {
                try {
                    if (connectedDataPeers.isNotEmpty()) {
                        val packet = engine.router.outboundQueue.poll()
                        if (packet != null) {
                            if (packet.isBroadcast) {
                                val encoded = MeshPacketCodec.encode(packet)
                                for (peerId in connectedDataPeers) {
                                    try {
                                        sendDataPacket(peerId, encoded)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to broadcast to $peerId", e)
                                    }
                                }
                            } else {
                                val destId = packet.destNodeId.lowercase()
                                if (connectedDataPeers.contains(destId)) {
                                    try {
                                        sendDataPacket(destId, MeshPacketCodec.encode(packet))
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to send packet to $destId", e)
                                    }
                                } else {
                                    // Peer is not in connectedDataPeers yet; re-enqueue after delay
                                    scope.launch {
                                        delay(300)
                                        engine.router.outboundQueue.enqueue(packet)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in data drainer loop", e)
                }
                delay(DATA_DRAIN_INTERVAL_MS)
            }
        }
    }

    /**
     * Dispatches received raw data bytes (a complete encoded MeshPacket) to the engine.
     */
    private fun dispatchReceivedData(rawBytes: ByteArray) {
        try {
            val packet = MeshPacketCodec.decode(rawBytes)
            if (packet != null) {
                Log.d(TAG, "dispatchReceivedData: decoded packet type=${packet.type} id=${packet.packetId} from ${packet.senderNodeId}")
                engine.processIncomingMeshPacket(packet)
            } else {
                Log.w(TAG, "dispatchReceivedData: failed to decode ${rawBytes.size}B")
            }
        } catch (e: Exception) {
            Log.e(TAG, "dispatchReceivedData exception", e)
        }
    }

    /** Server-side: notify a connected client with a data packet. */
    private fun notifyDataToClient(device: BluetoothDevice, peerNodeId: String, data: ByteArray) {
        val rxChar = rxCharacteristic ?: return
        val chunks = data.toList().chunked(MAX_CHAR_VALUE).map { it.toByteArray() }
        scope.launch {
            for ((i, chunk) in chunks.withIndex()) {
                val ok = sendNotification(device, rxChar, chunk)
                if (!ok) Log.w(TAG, "notifyDataToClient: chunk $i failed for $peerNodeId")
                delay(20)
            }
        }
    }

    /** GATT client: enqueue a write and drain. */
    private fun enqueueWrite(gatt: BluetoothGatt, peerNodeId: String, data: ByteArray) {
        val chunks = data.toList().chunked(MAX_CHAR_VALUE).map { it.toByteArray() }
        val queue = pendingWrites.getOrPut(peerNodeId) { ArrayDeque() }
        queue.addAll(chunks)
        if (writeInProgress[peerNodeId] != true) {
            drainWriteQueue(gatt, peerNodeId)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Discovery hook — called by BleMeshEngine.handleScanResult()
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Called whenever a peer is discovered via BLE scan.
     * Applies deterministic tie-breaker to decide who initiates the GATT connection.
     * Higher nodeId = GATT client (Initiator). Lower nodeId = GATT server (Responder, waits).
     */
    fun onPeerDiscovered(peerNodeId: String, device: BluetoothDevice) {
        val normId = peerNodeId.lowercase()
        val session = engine.noiseSessionManager.getSession(normId)
        if (session?.isHandshakeComplete == true) {
            connectedDataPeers.add(normId)
            ensureDataDrainerStarted()
            return
        }
        if (activeHandshakePeers.containsKey(normId)) return

        val localNodeId = engine.router.localNodeId.lowercase()

        if (localNodeId > normId) {
            // We are the initiator — connect as GATT client
            Log.d(TAG, "[$localNodeId] Acting as INITIATOR for peer $normId (we have higher ID)")
            activeHandshakePeers[normId] = true
            connectAsClient(normId, device)
        } else {
            // We are the responder — GATT server handles incoming connection
            Log.d(TAG, "[$localNodeId] Acting as RESPONDER for peer $normId (we have lower ID, waiting)")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // GATT Server setup
    // ─────────────────────────────────────────────────────────────────────

    private fun startGattServer() {
        val adapter = bluetoothManager?.adapter ?: return
        if (!adapter.isEnabled) return

        try {
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

            // TX characteristic: clients write handshake & data bytes here
            val txChar = BluetoothGattCharacteristic(
                CHAR_TX_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            // RX characteristic: server notifies clients with responses & data
            val rxChar = BluetoothGattCharacteristic(
                CHAR_RX_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            // Add CCCD descriptor so clients can subscribe to notifications
            val cccd = BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            rxChar.addDescriptor(cccd)
            rxCharacteristic = rxChar

            service.addCharacteristic(txChar)
            service.addCharacteristic(rxChar)

            gattServer?.addService(service)
            Log.d(TAG, "GATT server started with service $SERVICE_UUID")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GATT server", e)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val mac = device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT server: device connected $mac")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT server: device disconnected $mac")
                    val nodeId = serverSideDevices.remove(mac)
                    if (nodeId != null) {
                        activeHandshakePeers.remove(nodeId)
                        cancelTimeout(nodeId)
                        connectedDataPeers.remove(nodeId)
                        serverSideConnectedDevices.remove(nodeId)
                    }
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (characteristic.uuid != CHAR_TX_UUID || value == null || value.isEmpty()) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
                return
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }

            // Direct MeshPacket: magic 'N', 'B', version 0x01
            val isDirectMeshPacket = value.size >= MeshPacket.HEADER_SIZE &&
                    value[0] == MeshPacket.MAGIC_0 &&
                    value[1] == MeshPacket.MAGIC_1 &&
                    value[2] == MeshPacket.CURRENT_VERSION

            // Prefixed MeshPacket: 8-byte nodeId + magic 'N', 'B', version 0x01
            val isPrefixedMeshPacket = value.size >= 8 + MeshPacket.HEADER_SIZE &&
                    value[8] == MeshPacket.MAGIC_0 &&
                    value[9] == MeshPacket.MAGIC_1 &&
                    value[10] == MeshPacket.CURRENT_VERSION

            if (isDirectMeshPacket) {
                Log.d(TAG, "GATT server: received direct MeshPacket (${value.size}B)")
                val peerNodeId = serverSideDevices[device.address]
                if (peerNodeId != null) {
                    connectedDataPeers.add(peerNodeId.lowercase())
                    serverSideConnectedDevices[peerNodeId.lowercase()] = device
                    ensureDataDrainerStarted()
                }
                scope.launch { dispatchReceivedData(value) }
            } else if (isPrefixedMeshPacket) {
                val nodeIdBytes = value.copyOfRange(0, 8)
                val peerNodeId = bytesToHex(nodeIdBytes).lowercase()
                val packetBytes = value.copyOfRange(8, value.size)
                serverSideDevices[device.address] = peerNodeId
                serverSideConnectedDevices[peerNodeId] = device
                connectedDataPeers.add(peerNodeId)
                ensureDataDrainerStarted()
                Log.d(TAG, "GATT server: received prefixed MeshPacket (${packetBytes.size}B) from $peerNodeId")
                scope.launch { dispatchReceivedData(packetBytes) }
            } else if (value.size >= 9) {
                val nodeIdBytes = value.copyOfRange(0, 8)
                val peerNodeId = bytesToHex(nodeIdBytes).lowercase()
                val innerPayload = value.copyOfRange(8, value.size)
                serverSideDevices[device.address] = peerNodeId
                serverSideConnectedDevices[peerNodeId] = device

                if (connectedDataPeers.contains(peerNodeId)) {
                    Log.d(TAG, "GATT server: received data ${innerPayload.size}B from $peerNodeId")
                    scope.launch { dispatchReceivedData(innerPayload) }
                } else {
                    Log.d(TAG, "GATT server: received handshake ${innerPayload.size}B from $peerNodeId")
                    scope.launch { handleServerSideHandshake(device, peerNodeId, innerPayload) }
                }
            } else {
                Log.w(TAG, "GATT server: received short unhandled write (${value.size}B) from ${device.address}")
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            Log.d(TAG, "GATT server: CCCD written by ${device.address}")
        }
    }

    /**
     * Server-side handshake state machine.
     * Msg1 -> responds with Msg2. Msg3 -> completes handshake.
     */
    private fun handleServerSideHandshake(device: BluetoothDevice, peerNodeId: String, payload: ByteArray) {
        val normId = peerNodeId.lowercase()
        try {
            val result = engine.noiseSessionManager.processHandshakeBytes(
                peerNodeId = normId,
                isInitiatorSide = false,
                incomingBytes = payload
            )

            when (result) {
                is HandshakeStepResult.SendResponse -> {
                    Log.d(TAG, "GATT server: sending Msg2 (${result.responseBytes.size}B) to $normId via notification")
                    scheduleTimeout(normId)
                    notifyClient(device, normId, result.responseBytes)
                }
                is HandshakeStepResult.Complete -> {
                    Log.d(TAG, "GATT server: handshake COMPLETE with $normId — opening data channel")
                    cancelTimeout(normId)
                    activeHandshakePeers.remove(normId)
                    serverSideConnectedDevices[normId] = device
                    connectedDataPeers.add(normId)
                    engine.refreshPeerStatePublic(normId)
                    ensureDataDrainerStarted()
                }
                is HandshakeStepResult.Error -> {
                    Log.e(TAG, "GATT server: handshake error from $normId: ${result.message}")
                    cancelTimeout(normId)
                    activeHandshakePeers.remove(normId)
                    engine.noiseSessionManager.removeSession(normId)
                }
                is HandshakeStepResult.NoReply -> {
                    Log.w(TAG, "GATT server: no reply needed for payload from $normId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "GATT server: exception during handshake with $normId", e)
            cancelTimeout(normId)
            activeHandshakePeers.remove(normId)
            engine.noiseSessionManager.removeSession(normId)
        }
    }

    /**
     * Sends a notification to a connected client (server -> client direction).
     */
    private fun notifyClient(device: BluetoothDevice, peerNodeId: String, data: ByteArray) {
        val rxChar = rxCharacteristic ?: run {
            Log.e(TAG, "RX characteristic is null, cannot notify $peerNodeId")
            return
        }

        val nodeIdBytes = hexToBytes(engine.router.localNodeId)
        val frame = nodeIdBytes + data
        val chunks = frame.toList().chunked(MAX_CHAR_VALUE).map { it.toByteArray() }
        Log.d(TAG, "GATT server: notifying $peerNodeId in ${chunks.size} chunk(s)")

        scope.launch {
            for ((index, chunk) in chunks.withIndex()) {
                val sent = sendNotification(device, rxChar, chunk)
                if (!sent) {
                    Log.w(TAG, "GATT server: notifyCharacteristicChanged returned false for $peerNodeId chunk $index")
                }
                delay(20)
            }
        }
    }

    private fun sendNotification(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer?.notifyCharacteristicChanged(device, characteristic, false, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                gattServer?.notifyCharacteristicChanged(device, characteristic, false) ?: false
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendNotification failed", e)
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // GATT Client (Initiator side)
    // ─────────────────────────────────────────────────────────────────────

    private fun connectAsClient(peerNodeId: String, device: BluetoothDevice) {
        val normId = peerNodeId.lowercase()
        scheduleTimeout(normId)
        try {
            val gatt = device.connectGatt(context, false, buildClientCallback(normId), BluetoothDevice.TRANSPORT_LE)
            clientConnections[normId] = gatt
            Log.d(TAG, "GATT client: connectGatt() called for $normId")
        } catch (e: Exception) {
            Log.e(TAG, "GATT client: connectGatt() failed for $normId", e)
            activeHandshakePeers.remove(normId)
            cancelTimeout(normId)
        }
    }

    private fun buildClientCallback(peerNodeId: String): BluetoothGattCallback {
        val rxBuffer = mutableListOf<Byte>()
        val normId = peerNodeId.lowercase()

        return object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "GATT client: connected to $normId (status=$status)")
                        // Request MTU; fallback to discoverServices if request returns false
                        val requested = gatt.requestMtu(MTU_SIZE)
                        if (!requested) {
                            Log.w(TAG, "requestMtu returned false, proceeding to discoverServices")
                            gatt.discoverServices()
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "GATT client: disconnected from $normId (status=$status)")
                        clientConnections.remove(normId)
                        activeHandshakePeers.remove(normId)
                        connectedDataPeers.remove(normId)
                        cancelTimeout(normId)
                        try { gatt.close() } catch (_: Exception) {}
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.d(TAG, "GATT client: MTU negotiated to $mtu for $normId")
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "GATT client: service discovery failed for $normId (status=$status)")
                    disconnectAndCleanup(gatt, normId)
                    return
                }

                val service = gatt.getService(SERVICE_UUID)
                if (service == null) {
                    Log.e(TAG, "GATT client: mesh service not found on $normId")
                    disconnectAndCleanup(gatt, normId)
                    return
                }

                val rxChar = service.getCharacteristic(CHAR_RX_UUID)
                if (rxChar == null) {
                    Log.e(TAG, "GATT client: RX characteristic not found on $normId")
                    disconnectAndCleanup(gatt, normId)
                    return
                }

                val notifEnabled = gatt.setCharacteristicNotification(rxChar, true)
                Log.d(TAG, "GATT client: setCharacteristicNotification=$notifEnabled for $normId")

                val cccd = rxChar.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    Log.e(TAG, "GATT client: CCCD descriptor not found on $normId")
                    disconnectAndCleanup(gatt, normId)
                    return
                }

                val writeOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
                Log.d(TAG, "GATT client: writeDescriptor(CCCD)=$writeOk for $normId")
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                if (descriptor.uuid != CCCD_UUID) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "GATT client: CCCD write failed for $normId (status=$status)")
                    disconnectAndCleanup(gatt, normId)
                    return
                }

                Log.d(TAG, "GATT client: notifications enabled on $normId, sending Msg1")

                val msg1Bytes = engine.noiseSessionManager.initiateHandshakeBytes(normId)
                if (msg1Bytes == null) {
                    Log.e(TAG, "GATT client: failed to generate Msg1 for $normId")
                    disconnectAndCleanup(gatt, normId)
                    return
                }

                writeHandshakeToServer(gatt, normId, msg1Bytes)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "GATT client: characteristic write failed for $normId (status=$status)")
                }
                writeInProgress[normId] = false

                val queue = pendingWrites[normId]
                if (queue.isNullOrEmpty()) {
                    val session = engine.noiseSessionManager.getSession(normId)
                    if (session?.isHandshakeComplete == true && !connectedDataPeers.contains(normId)) {
                        Log.d(TAG, "GATT client: Msg3 fully written — handshake COMPLETE with $normId — opening data channel")
                        cancelTimeout(normId)
                        activeHandshakePeers.remove(normId)
                        connectedDataPeers.add(normId)
                        engine.refreshPeerStatePublic(normId)
                        ensureDataDrainerStarted()
                        return
                    }
                }

                drainWriteQueue(gatt, normId)
            }

            // Android 13+ (API 33+) callback providing explicit byte array
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                handleIncomingNotification(gatt, normId, characteristic, value)
            }

            // Legacy callback for Android 12 and below
            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                handleIncomingNotification(gatt, normId, characteristic, value)
            }

            private fun handleIncomingNotification(
                gatt: BluetoothGatt,
                peerId: String,
                characteristic: BluetoothGattCharacteristic,
                rawValue: ByteArray
            ) {
                if (characteristic.uuid != CHAR_RX_UUID || rawValue.isEmpty()) return

                // Direct MeshPacket: starts with magic 'N', 'B', version 0x01
                val isDirectMeshPacket = rawValue.size >= MeshPacket.HEADER_SIZE &&
                        rawValue[0] == MeshPacket.MAGIC_0 &&
                        rawValue[1] == MeshPacket.MAGIC_1 &&
                        rawValue[2] == MeshPacket.CURRENT_VERSION

                // Prefixed MeshPacket: starts at offset 8 with 'N', 'B', version 0x01
                val isPrefixedMeshPacket = rawValue.size >= 8 + MeshPacket.HEADER_SIZE &&
                        rawValue[8] == MeshPacket.MAGIC_0 &&
                        rawValue[9] == MeshPacket.MAGIC_1 &&
                        rawValue[10] == MeshPacket.CURRENT_VERSION

                if (isDirectMeshPacket) {
                    Log.d(TAG, "GATT client: received direct MeshPacket (${rawValue.size}B) from $peerId")
                    connectedDataPeers.add(peerId)
                    ensureDataDrainerStarted()
                    scope.launch { dispatchReceivedData(rawValue) }
                    return
                }

                if (isPrefixedMeshPacket) {
                    val packetBytes = rawValue.copyOfRange(8, rawValue.size)
                    Log.d(TAG, "GATT client: received prefixed MeshPacket (${packetBytes.size}B) from $peerId")
                    connectedDataPeers.add(peerId)
                    ensureDataDrainerStarted()
                    scope.launch { dispatchReceivedData(packetBytes) }
                    return
                }

                if (connectedDataPeers.contains(peerId)) {
                    Log.d(TAG, "GATT client: received data ${rawValue.size}B from $peerId")
                    scope.launch { dispatchReceivedData(rawValue) }
                    return
                }

                // Handshake phase: accumulate chunks prefixed with 8-byte server nodeId
                if (rawValue.size < 9) {
                    rxBuffer.addAll(rawValue.toList())
                } else {
                    val senderIdBytes = rawValue.copyOfRange(0, 8)
                    val senderId = bytesToHex(senderIdBytes).lowercase()
                    if (!senderId.equals(peerId, ignoreCase = true)) {
                        Log.w(TAG, "GATT client: notification from unexpected sender $senderId (expected $peerId)")
                        return
                    }
                    rxBuffer.addAll(rawValue.copyOfRange(8, rawValue.size).toList())
                }

                val assembledBytes = rxBuffer.toByteArray()
                if (assembledBytes.isEmpty()) return

                Log.d(TAG, "GATT client: received ${assembledBytes.size}B handshake notification from $peerId")

                scope.launch {
                    try {
                        val result = engine.noiseSessionManager.processHandshakeBytes(
                            peerNodeId = peerId,
                            isInitiatorSide = true,
                            incomingBytes = assembledBytes
                        )

                        when (result) {
                            is HandshakeStepResult.SendResponse -> {
                                Log.d(TAG, "GATT client: sending Msg3 (${result.responseBytes.size}B) to $peerId")
                                rxBuffer.clear()
                                writeHandshakeToServer(gatt, peerId, result.responseBytes)
                            }
                            is HandshakeStepResult.Complete -> {
                                Log.d(TAG, "GATT client: handshake COMPLETE with $peerId")
                                rxBuffer.clear()
                                cancelTimeout(peerId)
                                activeHandshakePeers.remove(peerId)
                                connectedDataPeers.add(peerId)
                                engine.refreshPeerStatePublic(peerId)
                                ensureDataDrainerStarted()
                            }
                            is HandshakeStepResult.Error -> {
                                Log.e(TAG, "GATT client: handshake error from $peerId: ${result.message}")
                                rxBuffer.clear()
                                disconnectAndCleanup(gatt, peerId)
                            }
                            is HandshakeStepResult.NoReply -> {
                                Log.w(TAG, "GATT client: unexpected NoReply for $peerId")
                                rxBuffer.clear()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "GATT client: exception processing notification from $peerId", e)
                        rxBuffer.clear()
                        disconnectAndCleanup(gatt, peerId)
                    }
                }
            }
        }
    }

    private fun writeHandshakeToServer(gatt: BluetoothGatt, peerNodeId: String, payload: ByteArray) {
        val nodeIdBytes = hexToBytes(engine.router.localNodeId)
        val frame = nodeIdBytes + payload

        val chunks = frame.toList().chunked(MAX_CHAR_VALUE).map { it.toByteArray() }
        Log.d(TAG, "GATT client: queuing ${chunks.size} handshake chunk(s) to write to $peerNodeId")

        val queue = pendingWrites.getOrPut(peerNodeId) { ArrayDeque() }
        queue.addAll(chunks)
        writeInProgress[peerNodeId] = false

        drainWriteQueue(gatt, peerNodeId)
    }

    private fun drainWriteQueue(gatt: BluetoothGatt, peerNodeId: String) {
        val queue = pendingWrites[peerNodeId] ?: return
        if (writeInProgress[peerNodeId] == true) return
        val chunk = queue.removeFirstOrNull() ?: return

        val service = gatt.getService(SERVICE_UUID) ?: return
        val txChar = service.getCharacteristic(CHAR_TX_UUID) ?: return

        writeInProgress[peerNodeId] = true

        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(txChar, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                txChar.value = chunk
                @Suppress("DEPRECATION")
                txChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(txChar)
            }
        } catch (e: Exception) {
            Log.e(TAG, "drainWriteQueue write failed for $peerNodeId", e)
            false
        }

        if (!ok) {
            writeInProgress[peerNodeId] = false
            Log.w(TAG, "GATT client: writeCharacteristic returned false for $peerNodeId, retrying in 50ms")
            queue.addFirst(chunk)
            scope.launch {
                delay(50)
                drainWriteQueue(gatt, peerNodeId)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Timeout management
    // ─────────────────────────────────────────────────────────────────────

    private fun scheduleTimeout(peerNodeId: String) {
        cancelTimeout(peerNodeId)
        val job = scope.launch {
            delay(HANDSHAKE_TIMEOUT_MS)
            Log.w(TAG, "Handshake TIMEOUT for $peerNodeId — resetting and retrying")
            engine.noiseSessionManager.removeSession(peerNodeId)
            activeHandshakePeers.remove(peerNodeId)
            pendingWrites.remove(peerNodeId)
            writeInProgress.remove(peerNodeId)

            clientConnections.remove(peerNodeId)?.let { gatt ->
                try { gatt.disconnect(); gatt.close() } catch (_: Exception) {}
            }
        }
        timeoutJobs[peerNodeId] = job
    }

    private fun cancelTimeout(peerNodeId: String) {
        timeoutJobs.remove(peerNodeId)?.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun disconnectAndCleanup(gatt: BluetoothGatt, peerNodeId: String) {
        cancelTimeout(peerNodeId)
        activeHandshakePeers.remove(peerNodeId)
        connectedDataPeers.remove(peerNodeId)
        serverSideConnectedDevices.remove(peerNodeId)
        pendingWrites.remove(peerNodeId)
        writeInProgress.remove(peerNodeId)
        clientConnections.remove(peerNodeId)
        engine.noiseSessionManager.removeSession(peerNodeId)
        try { gatt.disconnect(); gatt.close() } catch (_: Exception) {}
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
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}

sealed class HandshakeStepResult {
    data class SendResponse(val responseBytes: ByteArray) : HandshakeStepResult()
    object Complete : HandshakeStepResult()
    object NoReply : HandshakeStepResult()
    data class Error(val message: String) : HandshakeStepResult()
}
