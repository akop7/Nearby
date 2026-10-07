package org.nearby.mesh.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.BatteryManager
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.nearby.mesh.crypto.NodeIdentity
import org.nearby.mesh.domain.AttachmentType
import org.nearby.mesh.domain.AudioController
import org.nearby.mesh.domain.DeliveryStatus
import org.nearby.mesh.domain.DiscoveredPeer
import org.nearby.mesh.domain.FileTransferConstants
import org.nearby.mesh.domain.GeoCoordinates
import org.nearby.mesh.domain.MeshRepository
import org.nearby.mesh.domain.MessageStore
import org.nearby.mesh.domain.PeerTrustManager
import org.nearby.mesh.domain.PeerTrustState
import org.nearby.mesh.domain.SosAlert
import org.nearby.mesh.domain.SosCodec
import org.nearby.mesh.domain.TextMessage
import org.nearby.mesh.domain.TextMessageCodec
import org.nearby.mesh.domain.TransferAccept
import org.nearby.mesh.domain.TransferAcceptCodec
import org.nearby.mesh.domain.TransferOffer
import org.nearby.mesh.domain.TransferOfferCodec
import org.nearby.mesh.domain.TransferProgress
import org.nearby.mesh.noise.NoiseCrypto
import org.nearby.mesh.noise.NoiseSessionManager
import org.nearby.mesh.protocol.MessageChunker
import org.nearby.mesh.protocol.MeshPacket
import org.nearby.mesh.protocol.PacketReassembler
import org.nearby.mesh.protocol.PacketType
import org.nearby.mesh.routing.MeshRouter
import org.nearby.mesh.routing.RoutingAction
import org.nearby.mesh.voice.AndroidAudioController
import org.nearby.mesh.wifi.FileTransferManager
import org.nearby.mesh.wifi.WifiDirectManager
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE Mesh Engine responsible for local advertising, scanning, and maintaining
 * the neighbor table of reachable peers without a central coordinator (PRD §5.2, Architecture §3.2).
 * Implements the [MeshRepository] domain contract.
 */
class BleMeshEngine(
    private val context: Context,
    private val localIdentity: NodeIdentity,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : MeshRepository {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    val noiseSessionManager: NoiseSessionManager = NoiseSessionManager(localIdentity)
    val peerTrustManager: PeerTrustManager = PeerTrustManager(context)
    val router: MeshRouter = MeshRouter(localIdentity.nodeId)
    val messageStore: MessageStore = MessageStore()
    val wifiDirectManager: WifiDirectManager = WifiDirectManager(context)
    val fileTransferManager: FileTransferManager = FileTransferManager(context, noiseSessionManager, wifiDirectManager)

    // GATT transport layer — drives the physical Noise_XX handshake over BLE connections
    val gattTransport: BleGattTransport = BleGattTransport(context, this)

    private val packetReassembler = PacketReassembler()
    private val secureRandom = SecureRandom()

    private val _peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val peers: StateFlow<List<DiscoveredPeer>> = _peers.asStateFlow()
    override val activeSosAlerts: StateFlow<List<SosAlert>> = messageStore.activeSosAlerts
    override val activeTransfers: StateFlow<Map<String, TransferProgress>> = fileTransferManager.activeTransfers
    override val audioController: AudioController = AndroidAudioController(context)

    // Alias for backward compatibility if needed
    val neighbors: StateFlow<List<DiscoveredPeer>> get() = peers

    private val neighborMap = ConcurrentHashMap<String, DiscoveredPeer>()

    private var isRunning = false
    private var maintenanceJob: Job? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            // Advertising started successfully
        }

        override fun onStartFailure(errorCode: Int) {
            // Handled gracefully; will retry on next cycle if active
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { handleScanResult(it) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            // Handled gracefully
        }
    }

    @Synchronized
    override fun start() {
        if (isRunning) return
        isRunning = true

        startAdvertising()
        startScanning()
        startMaintenanceLoop()
        gattTransport.start()
    }

    @Synchronized
    override fun stop() {
        if (!isRunning) return
        isRunning = false

        stopAdvertising()
        stopScanning()
        maintenanceJob?.cancel()
        maintenanceJob = null
        gattTransport.stop()

        fileTransferManager.activeTransfers.value.keys.forEach {
            fileTransferManager.cancelTransfer(it)
        }
        audioController.stopVoiceNote()
        audioController.cancelRecording()
        wifiDirectManager.release()
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val serviceData = buildAnnouncementPayload()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(SERVICE_PARCEL_UUID, serviceData)
            .build()

        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            // Permission not granted
        } catch (e: Exception) {
            // Hardware/Bluetooth state issue
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            // Ignore on cleanup
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return

        scanner = bluetoothAdapter.bluetoothLeScanner ?: return

        val filter = ScanFilter.Builder()
            .setServiceData(SERVICE_PARCEL_UUID, byteArrayOf(PROTOCOL_VERSION), byteArrayOf(0xFF.toByte()))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            // Permission not granted
        } catch (e: Exception) {
            // Hardware/Bluetooth state issue
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            // Ignore on cleanup
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val serviceData = record.getServiceData(SERVICE_PARCEL_UUID) ?: return

        if (serviceData.size < 9) return // 1 byte version + 8 bytes nodeId minimum
        if (serviceData[0] != PROTOCOL_VERSION) return

        val buffer = ByteBuffer.wrap(serviceData)
        buffer.get() // Skip version

        val nodeIdBytes = ByteArray(8)
        buffer.get(nodeIdBytes)
        val discoveredNodeId = bytesToHex(nodeIdBytes)

        // Ignore our own broadcast
        if (discoveredNodeId.equals(localIdentity.nodeId, ignoreCase = true)) {
            return
        }

        val nameBytes = ByteArray(buffer.remaining())
        buffer.get(nameBytes)
        val discoveredName = if (nameBytes.isNotEmpty()) {
            String(nameBytes, Charsets.UTF_8)
        } else {
            "Survivor-${discoveredNodeId.takeLast(4).uppercase()}"
        }

        val now = System.currentTimeMillis()
        val session = noiseSessionManager.getSession(discoveredNodeId)
        val peerStaticKey = session?.peerStaticPublicKey
        val rawTrust = peerTrustManager.evaluateTrust(discoveredNodeId, peerStaticKey)
        val isEncrypted = session?.isHandshakeComplete == true
        val trustState = if (rawTrust == PeerTrustState.VERIFIED || rawTrust == PeerTrustState.COMPROMISED) {
            rawTrust
        } else if (isEncrypted) {
            PeerTrustState.ENCRYPTED
        } else {
            PeerTrustState.UNVERIFIED
        }
        val sasCode = session?.sasCode

        val neighbor = DiscoveredPeer(
            nodeId = discoveredNodeId,
            displayName = discoveredName,
            rssi = result.rssi,
            lastSeenMs = now,
            trustState = trustState,
            isEncrypted = isEncrypted,
            sasCode = sasCode
        )

        neighborMap[discoveredNodeId] = neighbor
        _peers.value = neighborMap.values.toList()

        // Delegate handshake initiation to the GATT transport which applies the deterministic
        // tie-breaker (higher nodeId = GATT client/initiator) and manages the full pipeline.
        // This replaces the old in-memory-queue approach that never sent bytes to the peer.
        gattTransport.onPeerDiscovered(discoveredNodeId, result.device)
    }

    override fun markPeerVerified(nodeId: String, customLabel: String?) {
        val session = noiseSessionManager.getSession(nodeId)
        val key = session?.peerStaticPublicKey ?: ByteArray(0)
        peerTrustManager.markVerified(nodeId, key, customLabel)
        refreshPeerState(nodeId)
    }

    override fun markPeerCompromised(nodeId: String) {
        peerTrustManager.markCompromised(nodeId)
        refreshPeerState(nodeId)
    }

    override fun getTimeline(peerNodeId: String): StateFlow<List<TextMessage>> =
        messageStore.getTimeline(peerNodeId)

    override fun sendDirectMessage(peerNodeId: String, text: String): TextMessage {
        val now = System.currentTimeMillis()
        val messageId = secureRandom.nextLong()

        val isSessionEstablished = noiseSessionManager.isSessionEstablished(peerNodeId)
        val initialMessage = TextMessage(
            messageId = messageId,
            senderNodeId = localIdentity.nodeId,
            destNodeId = peerNodeId,
            senderDisplayName = localIdentity.displayName,
            content = text,
            timestampMs = now,
            isOutgoing = true,
            deliveryStatus = DeliveryStatus.SENDING,
            isEncrypted = isSessionEstablished,
            hopCount = 0
        )
        messageStore.addMessage(initialMessage)

        scope.launch(Dispatchers.IO) {
            try {
                val encodedPayload = TextMessageCodec.encode(localIdentity.displayName, text, now)

                val payloadToSend = if (noiseSessionManager.isSessionEstablished(peerNodeId)) {
                    noiseSessionManager.encrypt(peerNodeId, encodedPayload)
                } else {
                    // Trigger handshake if session pending
                    val handshakePacket = noiseSessionManager.initiateHandshake(peerNodeId)
                    if (handshakePacket != null) {
                        router.sendLocalPacket(handshakePacket, now)
                    }
                    encodedPayload
                }

                val packets = MessageChunker.chunkMessage(
                    type = PacketType.TEXT_MESSAGE,
                    ttl = MeshPacket.DEFAULT_TTL,
                    senderNodeId = localIdentity.nodeId,
                    destNodeId = peerNodeId,
                    payload = payloadToSend,
                    packetId = messageId
                )

                packets.forEach { packet ->
                    router.sendLocalPacket(packet, now)
                }

                val sentMessage = initialMessage.copy(
                    deliveryStatus = DeliveryStatus.SENT,
                    isEncrypted = noiseSessionManager.isSessionEstablished(peerNodeId)
                )
                messageStore.addMessage(sentMessage)
            } catch (e: Exception) {
                android.util.Log.e("BleMeshEngine", "sendDirectMessage failed for peer $peerNodeId", e)
                val failedMessage = initialMessage.copy(deliveryStatus = DeliveryStatus.FAILED)
                messageStore.addMessage(failedMessage)
            }
        }

        return initialMessage
    }

    override fun sendFile(peerNodeId: String, file: File): String? {
        val offer = fileTransferManager.createOutgoingOffer(file, peerNodeId, isVoiceNote = false) ?: return null
        val messageId = offer.transferId.hashCode().toLong()
        val now = System.currentTimeMillis()

        val initialMessage = TextMessage(
            messageId = messageId,
            senderNodeId = localIdentity.nodeId,
            destNodeId = peerNodeId,
            senderDisplayName = localIdentity.displayName,
            content = file.name,
            timestampMs = now,
            isOutgoing = true,
            deliveryStatus = DeliveryStatus.SENDING,
            isEncrypted = true,
            hopCount = 0,
            attachmentType = AttachmentType.FILE,
            attachmentPath = file.absolutePath,
            attachmentName = file.name,
            attachmentSizeBytes = file.length(),
            transferId = offer.transferId
        )
        messageStore.addMessage(initialMessage)

        val encodedOffer = TransferOfferCodec.encode(offer)
        val packets = MessageChunker.chunkMessage(
            type = PacketType.TRANSFER_OFFER,
            ttl = MeshPacket.DEFAULT_TTL,
            senderNodeId = localIdentity.nodeId,
            destNodeId = peerNodeId,
            payload = encodedOffer
        )
        packets.forEach { router.sendLocalPacket(it, now) }

        wifiDirectManager.createAutonomousGroup(
            onReady = { _, _ -> },
            onError = { _ -> }
        )

        return offer.transferId
    }

    override fun sendVoiceNote(peerNodeId: String, file: File, durationMs: Long): String? {
        val offer = fileTransferManager.createOutgoingOffer(file, peerNodeId, isVoiceNote = true, voiceDurationMs = durationMs) ?: return null
        val messageId = offer.transferId.hashCode().toLong()
        val now = System.currentTimeMillis()

        val initialMessage = TextMessage(
            messageId = messageId,
            senderNodeId = localIdentity.nodeId,
            destNodeId = peerNodeId,
            senderDisplayName = localIdentity.displayName,
            content = "Voice Note (${(durationMs / 1000).coerceAtLeast(1)}s)",
            timestampMs = now,
            isOutgoing = true,
            deliveryStatus = DeliveryStatus.SENDING,
            isEncrypted = true,
            hopCount = 0,
            attachmentType = AttachmentType.VOICE_NOTE,
            attachmentPath = file.absolutePath,
            attachmentName = file.name,
            attachmentSizeBytes = file.length(),
            attachmentDurationMs = durationMs,
            transferId = offer.transferId
        )
        messageStore.addMessage(initialMessage)

        val encodedOffer = TransferOfferCodec.encode(offer)
        val packets = MessageChunker.chunkMessage(
            type = PacketType.TRANSFER_OFFER,
            ttl = MeshPacket.DEFAULT_TTL,
            senderNodeId = localIdentity.nodeId,
            destNodeId = peerNodeId,
            payload = encodedOffer
        )
        packets.forEach { router.sendLocalPacket(it, now) }

        wifiDirectManager.createAutonomousGroup(
            onReady = { _, _ -> },
            onError = { _ -> }
        )

        return offer.transferId
    }

    override fun cancelTransfer(transferId: String) {
        fileTransferManager.cancelTransfer(transferId)
    }

    override fun broadcastSos(
        distressNote: String,
        location: GeoCoordinates?,
        isMedical: Boolean,
        isTrapped: Boolean
    ): SosAlert {
        val now = System.currentTimeMillis()
        val alertId = secureRandom.nextLong()
        val battery = getBatteryPercent()

        val alert = SosAlert(
            alertId = alertId,
            senderNodeId = localIdentity.nodeId,
            senderDisplayName = localIdentity.displayName,
            timestampMs = now,
            batteryPercent = battery,
            location = location,
            distressMessage = distressNote,
            isMedicalEmergency = isMedical,
            isTrapped = isTrapped,
            hopCount = 0
        )
        messageStore.addSosAlert(alert)

        val encodedPayload = SosCodec.encode(alert)
        val packets = MessageChunker.chunkMessage(
            type = PacketType.SOS_ALERT,
            ttl = MeshPacket.SOS_TTL,
            senderNodeId = localIdentity.nodeId,
            destNodeId = MeshPacket.BROADCAST_NODE_ID,
            payload = encodedPayload,
            packetId = alertId
        )

        packets.forEach { packet ->
            router.sendLocalPacket(packet, now)
        }

        return alert
    }

    override fun dismissSos(alertId: Long) {
        messageStore.dismissSosAlert(alertId)
    }

    fun processIncomingMeshPacket(packet: MeshPacket): MeshPacket? {
        val action = router.routeIncomingPacket(packet)

        when (action) {
            is RoutingAction.Drop -> return null
            is RoutingAction.ForwardRelay -> return null // Already enqueued into router.outboundQueue
            is RoutingAction.DeliverLocal -> handleLocalDelivery(action.packet)
            is RoutingAction.DeliverAndForward -> {
                handleLocalDelivery(action.localPacket)
                // action.relayPacket is already enqueued in router.outboundQueue
            }
        }
        return null
    }

    private fun handleLocalDelivery(packet: MeshPacket) {
        when (packet.type) {
            PacketType.NOISE_HANDSHAKE -> {
                scope.launch(Dispatchers.IO) {
                    try {
                        val response = noiseSessionManager.handleIncomingHandshakePacket(packet)
                        refreshPeerState(packet.senderNodeId)
                        if (response != null) {
                            router.sendLocalPacket(response)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("BleMeshEngine", "Error handling incoming handshake packet from ${packet.senderNodeId}", e)
                    }
                }
            }
            PacketType.SOS_ALERT -> {
                val completePayload = packetReassembler.addChunk(packet)
                if (completePayload != null) {
                    val alert = SosCodec.decode(packet.packetId, packet.senderNodeId, packet.hopCount.toInt(), completePayload)
                    if (alert != null) {
                        messageStore.addSosAlert(alert)
                    }
                }
            }
            PacketType.TEXT_MESSAGE -> {
                val completePayload = packetReassembler.addChunk(packet)
                if (completePayload != null) {
                    val decryptedBytes = try {
                        noiseSessionManager.decrypt(packet.senderNodeId, completePayload)
                    } catch (e: Exception) {
                        completePayload
                    }
                    val msg = TextMessageCodec.decode(
                        messageId = packet.packetId,
                        senderNodeId = packet.senderNodeId,
                        destNodeId = packet.destNodeId,
                        isOutgoing = false,
                        hopCount = packet.hopCount.toInt(),
                        bytes = decryptedBytes
                    )
                    if (msg != null) {
                        messageStore.addMessage(msg)
                        // Send ACK back so sender's UI shows DELIVERED
                        val ackPacket = MeshPacket(
                            type = PacketType.ACK,
                            ttl = MeshPacket.DEFAULT_TTL,
                            hopCount = 0,
                            packetId = packet.packetId,
                            senderNodeId = localIdentity.nodeId,
                            destNodeId = packet.senderNodeId,
                            chunkIndex = 0,
                            chunkCount = 1,
                            payload = ByteArray(0)
                        )
                        router.sendLocalPacket(ackPacket)
                    }
                }
            }
            PacketType.ACK -> {
                messageStore.updateDeliveryStatus(packet.senderNodeId, packet.packetId, DeliveryStatus.DELIVERED)
            }
            PacketType.TRANSFER_OFFER -> {
                val completePayload = packetReassembler.addChunk(packet)
                if (completePayload != null) {
                    val offer = TransferOfferCodec.decode(completePayload)
                    if (offer != null) {
                        handleIncomingTransferOffer(packet.senderNodeId, offer)
                    }
                }
            }
            PacketType.TRANSFER_ACCEPT -> {
                val completePayload = packetReassembler.addChunk(packet)
                if (completePayload != null) {
                    val accept = TransferAcceptCodec.decode(completePayload)
                    if (accept != null) {
                        handleIncomingTransferAccept(packet.senderNodeId, accept)
                    }
                }
            }
            else -> {}
        }
    }

    private fun handleIncomingTransferOffer(senderNodeId: String, offer: TransferOffer) {
        val accept = fileTransferManager.handleIncomingOffer(offer, senderNodeId)
        val messageId = offer.transferId.hashCode().toLong()
        val now = System.currentTimeMillis()

        val placeholderMessage = TextMessage(
            messageId = messageId,
            senderNodeId = senderNodeId,
            destNodeId = localIdentity.nodeId,
            senderDisplayName = neighborMap[senderNodeId]?.displayName ?: "Nearby Peer",
            content = if (offer.isVoiceNote) "Voice Note (${(offer.voiceDurationMs / 1000).coerceAtLeast(1)}s)" else offer.fileName,
            timestampMs = now,
            isOutgoing = false,
            deliveryStatus = DeliveryStatus.SENDING,
            isEncrypted = true,
            hopCount = 1,
            attachmentType = if (offer.isVoiceNote) AttachmentType.VOICE_NOTE else AttachmentType.FILE,
            attachmentPath = null,
            attachmentName = offer.fileName,
            attachmentSizeBytes = offer.fileSizeBytes,
            attachmentDurationMs = offer.voiceDurationMs,
            transferId = offer.transferId
        )
        messageStore.addMessage(placeholderMessage)

        if (!accept.accepted) {
            val acceptPayload = TransferAcceptCodec.encode(accept)
            val packets = MessageChunker.chunkMessage(
                type = PacketType.TRANSFER_ACCEPT,
                ttl = MeshPacket.DEFAULT_TTL,
                senderNodeId = localIdentity.nodeId,
                destNodeId = senderNodeId,
                payload = acceptPayload
            )
            packets.forEach { router.sendLocalPacket(it, now) }
            return
        }

        val linkState = wifiDirectManager.linkState.value
        val hostIp = linkState.groupOwnerAddress ?: wifiDirectManager.getLocalIpAddress() ?: "192.168.49.1"
        val updatedAccept = accept.copy(groupOwnerIp = hostIp)

        val acceptPayload = TransferAcceptCodec.encode(updatedAccept)
        val packets = MessageChunker.chunkMessage(
            type = PacketType.TRANSFER_ACCEPT,
            ttl = MeshPacket.DEFAULT_TTL,
            senderNodeId = localIdentity.nodeId,
            destNodeId = senderNodeId,
            payload = acceptPayload
        )
        packets.forEach { router.sendLocalPacket(it, now) }

        fileTransferManager.startReceiverStream(
            transferId = offer.transferId,
            host = hostIp,
            port = updatedAccept.port,
            isServer = true
        ) { success, file, _ ->
            val finalStatus = if (success) DeliveryStatus.DELIVERED else DeliveryStatus.FAILED
            val updatedMsg = placeholderMessage.copy(
                deliveryStatus = finalStatus,
                attachmentPath = file?.absolutePath
            )
            messageStore.addMessage(updatedMsg)
        }
    }

    private fun handleIncomingTransferAccept(senderNodeId: String, accept: TransferAccept) {
        if (!accept.accepted) {
            fileTransferManager.cancelTransfer(accept.transferId)
            val msg = messageStore.getTimeline(senderNodeId).value.find { it.transferId == accept.transferId }
            if (msg != null) {
                messageStore.addMessage(msg.copy(deliveryStatus = DeliveryStatus.FAILED))
            }
            return
        }

        val targetHost = if (accept.groupOwnerIp.isNotBlank()) accept.groupOwnerIp else "192.168.49.1"
        fileTransferManager.startSenderStream(
            transferId = accept.transferId,
            host = targetHost,
            port = accept.port,
            isServer = false
        ) { success, _ ->
            val msg = messageStore.getTimeline(senderNodeId).value.find { it.transferId == accept.transferId }
            if (msg != null) {
                val status = if (success) DeliveryStatus.DELIVERED else DeliveryStatus.FAILED
                messageStore.addMessage(msg.copy(deliveryStatus = status))
            }
        }
    }

    private fun getBatteryPercent(): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 100
        } catch (e: Exception) {
            100
        }
    }

    private fun refreshPeerState(nodeId: String) {
        val existing = neighborMap[nodeId] ?: return
        val session = noiseSessionManager.getSession(nodeId)
        val rawTrust = peerTrustManager.evaluateTrust(nodeId, session?.peerStaticPublicKey)
        val isEncrypted = session?.isHandshakeComplete == true
        val trustState = if (rawTrust == PeerTrustState.VERIFIED || rawTrust == PeerTrustState.COMPROMISED) {
            rawTrust
        } else if (isEncrypted) {
            PeerTrustState.ENCRYPTED
        } else {
            PeerTrustState.UNVERIFIED
        }
        val updated = existing.copy(
            trustState = trustState,
            isEncrypted = isEncrypted,
            sasCode = session?.sasCode
        )
        neighborMap[nodeId] = updated
        _peers.value = neighborMap.values.toList()
    }

    /**
     * Public alias for [refreshPeerState], called by [BleGattTransport] after a handshake
     * completes over the GATT pipeline to immediately promote the peer to ENCRYPTED state.
     */
    fun refreshPeerStatePublic(nodeId: String) = refreshPeerState(nodeId)

    /**
     * Checks if a packet has already been received recently to prevent relay loops.
     */
    fun isDuplicatePacket(packetId: Long): Boolean {
        return router.dedupCache.contains(packetId)
    }

    private fun buildAnnouncementPayload(): ByteArray {
        val nodeIdBytes = hexToBytes(localIdentity.nodeId)
        val nameBytes = localIdentity.displayName.toByteArray(Charsets.UTF_8)
        val safeNameBytes = if (nameBytes.size > MAX_ADVERTISE_NAME_BYTES) {
            nameBytes.copyOfRange(0, MAX_ADVERTISE_NAME_BYTES)
        } else {
            nameBytes
        }

        val payload = ByteBuffer.allocate(1 + nodeIdBytes.size + safeNameBytes.size)
        payload.put(PROTOCOL_VERSION)
        payload.put(nodeIdBytes)
        payload.put(safeNameBytes)
        return payload.array()
    }

    private fun startMaintenanceLoop() {
        maintenanceJob?.cancel()
        maintenanceJob = scope.launch {
            while (isActive) {
                delay(MAINTENANCE_INTERVAL_MS)
                pruneStaleNeighbors()
                pruneDedupCache()
            }
        }
    }

    private fun pruneStaleNeighbors() {
        val now = System.currentTimeMillis()
        var changed = false
        val iterator = neighborMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastSeenMs > NEIGHBOR_TIMEOUT_MS) {
                iterator.remove()
                changed = true
            }
        }
        if (changed) {
            _peers.value = neighborMap.values.toList()
        }
    }

    private fun pruneDedupCache() {
        val now = System.currentTimeMillis()
        router.dedupCache.evictExpired(now)
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

    companion object {
        const val PROTOCOL_VERSION: Byte = 0x01
        private const val MAX_ADVERTISE_NAME_BYTES = 12
        private const val NEIGHBOR_TIMEOUT_MS = 20_000L
        private const val DEDUP_EXPIRY_MS = 120_000L
        private const val MAINTENANCE_INTERVAL_MS = 5_000L

        // Nearby Mesh BLE 128-bit Service UUID
        val SERVICE_UUID: UUID = UUID.fromString("0000fe26-0000-1000-8000-00805f9b34fb")
        val SERVICE_PARCEL_UUID = ParcelUuid(SERVICE_UUID)
    }
}
