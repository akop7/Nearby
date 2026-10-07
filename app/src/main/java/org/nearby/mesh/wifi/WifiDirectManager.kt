package org.nearby.mesh.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * State of the Wi-Fi Direct P2P radio link.
 */
data class WifiDirectLinkState(
    val isEnabled: Boolean = false,
    val isConnected: Boolean = false,
    val isGroupOwner: Boolean = false,
    val groupOwnerAddress: String? = null,
    val localIpAddress: String? = null
)

/**
 * Manages Wi-Fi Direct (Wi-Fi P2P) discovery, group negotiation, and lifecycle teardown.
 */
class WifiDirectManager(private val context: Context) {

    private val p2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private var channel: WifiP2pManager.Channel? = null
    private var isReceiverRegistered = false

    private val _linkState = MutableStateFlow(WifiDirectLinkState())
    val linkState: StateFlow<WifiDirectLinkState> = _linkState.asStateFlow()

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    _linkState.value = _linkState.value.copy(isEnabled = isEnabled)
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        requestConnectionInfo()
                    } else {
                        _linkState.value = _linkState.value.copy(
                            isConnected = false,
                            isGroupOwner = false,
                            groupOwnerAddress = null
                        )
                    }
                }
            }
        }
    }

    init {
        initialize()
    }

    @Synchronized
    fun initialize() {
        if (channel != null || p2pManager == null) return

        try {
            channel = p2pManager.initialize(context, Looper.getMainLooper(), null)
            val intentFilter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }

            if (!isReceiverRegistered) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(p2pReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(p2pReceiver, intentFilter)
                }
                isReceiverRegistered = true
            }
        } catch (e: Exception) {
            channel = null
        }
    }

    /**
     * Creates a autonomous Wi-Fi Direct group with this device as Group Owner (GO).
     */
    @SuppressLint("MissingPermission")
    fun createAutonomousGroup(
        onReady: (isGroupOwner: Boolean, groupOwnerIp: String) -> Unit,
        onError: (String) -> Unit
    ) {
        val mgr = p2pManager
        val ch = channel
        if (mgr == null || ch == null) {
            onError("Wi-Fi Direct is not supported or initialized")
            return
        }

        try {
            mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    requestConnectionInfo { info ->
                        if (info != null && info.groupFormed) {
                            val ip = info.groupOwnerAddress?.hostAddress ?: getLocalIpAddress() ?: "192.168.49.1"
                            _linkState.value = _linkState.value.copy(
                                isConnected = true,
                                isGroupOwner = info.isGroupOwner,
                                groupOwnerAddress = ip,
                                localIpAddress = ip
                            )
                            onReady(info.isGroupOwner, ip)
                        } else {
                            val fallbackIp = getLocalIpAddress() ?: "192.168.49.1"
                            onReady(true, fallbackIp)
                        }
                    }
                }

                override fun onFailure(reason: Int) {
                    // Reason 2 = BUSY (group might already exist)
                    if (reason == WifiP2pManager.BUSY) {
                        requestConnectionInfo { info ->
                            val ip = info?.groupOwnerAddress?.hostAddress ?: getLocalIpAddress() ?: "192.168.49.1"
                            onReady(info?.isGroupOwner ?: true, ip)
                        }
                    } else {
                        onError("Failed to create Wi-Fi Direct group (error code: $reason)")
                    }
                }
            })
        } catch (e: SecurityException) {
            onError("Missing Wi-Fi Direct permissions: ${e.message}")
        } catch (e: Exception) {
            onError("Wi-Fi Direct error: ${e.message}")
        }
    }

    /**
     * Connects to a Wi-Fi Direct peer device using its MAC address.
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(
        deviceAddress: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val mgr = p2pManager
        val ch = channel
        if (mgr == null || ch == null) {
            onError("Wi-Fi Direct not initialized")
            return
        }

        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
        }

        try {
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    onSuccess()
                }

                override fun onFailure(reason: Int) {
                    onError("Failed to connect to peer (code: $reason)")
                }
            })
        } catch (e: SecurityException) {
            onError("Missing permissions: ${e.message}")
        } catch (e: Exception) {
            onError("Connection error: ${e.message}")
        }
    }

    /**
     * Requests current P2P connection information.
     */
    @SuppressLint("MissingPermission")
    fun requestConnectionInfo(onResult: ((WifiP2pInfo?) -> Unit)? = null) {
        val mgr = p2pManager ?: return
        val ch = channel ?: return
        try {
            mgr.requestConnectionInfo(ch) { info ->
                if (info != null) {
                    val ownerIp = info.groupOwnerAddress?.hostAddress
                    val localIp = getLocalIpAddress()
                    _linkState.value = _linkState.value.copy(
                        isConnected = info.groupFormed,
                        isGroupOwner = info.isGroupOwner,
                        groupOwnerAddress = ownerIp,
                        localIpAddress = localIp
                    )
                }
                onResult?.invoke(info)
            }
        } catch (ignored: Exception) {
            onResult?.invoke(null)
        }
    }

    /**
     * Tears down the active Wi-Fi Direct group to conserve device battery (Architecture.md §3.3).
     */
    fun teardownGroup(onComplete: (() -> Unit)? = null) {
        val mgr = p2pManager
        val ch = channel
        if (mgr == null || ch == null) {
            _linkState.value = _linkState.value.copy(isConnected = false, isGroupOwner = false, groupOwnerAddress = null)
            onComplete?.invoke()
            return
        }

        try {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _linkState.value = _linkState.value.copy(
                        isConnected = false,
                        isGroupOwner = false,
                        groupOwnerAddress = null
                    )
                    onComplete?.invoke()
                }

                override fun onFailure(reason: Int) {
                    _linkState.value = _linkState.value.copy(
                        isConnected = false,
                        isGroupOwner = false,
                        groupOwnerAddress = null
                    )
                    onComplete?.invoke()
                }
            })
        } catch (ignored: Exception) {
            _linkState.value = _linkState.value.copy(
                isConnected = false,
                isGroupOwner = false,
                groupOwnerAddress = null
            )
            onComplete?.invoke()
        }
    }

    /**
     * Scans local network interfaces to determine active IPv4 addresses (e.g. p2p0, wlan0).
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallbackIp: String? = null

            for (networkInterface in interfaces) {
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                for (inetAddress in addresses) {
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        val ip = inetAddress.hostAddress ?: continue
                        if (networkInterface.name.startsWith("p2p", ignoreCase = true)) {
                            return ip // Prefer P2P interface
                        }
                        if (networkInterface.name.startsWith("wlan", ignoreCase = true)) {
                            fallbackIp = ip
                        }
                    }
                }
            }
            return fallbackIp
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Releases listeners and resources.
     */
    fun release() {
        teardownGroup()
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(p2pReceiver)
            } catch (ignored: Exception) {}
            isReceiverRegistered = false
        }
        channel = null
    }
}
