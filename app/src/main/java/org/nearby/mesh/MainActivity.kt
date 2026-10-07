package org.nearby.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import org.nearby.mesh.ble.BleMeshEngine
import org.nearby.mesh.crypto.IdentityManager
import org.nearby.mesh.crypto.NodeIdentity
import org.nearby.mesh.domain.DiscoveredPeer
import org.nearby.mesh.domain.MeshRepository
import org.nearby.mesh.domain.SosAlert
import org.nearby.mesh.service.MeshForegroundService
import org.nearby.mesh.ui.OnboardingScreen
import org.nearby.mesh.ui.PeerChatScreen
import org.nearby.mesh.ui.PeerRadarScreen
import org.nearby.mesh.ui.SosBanner
import org.nearby.mesh.ui.SosComposeDialog
import org.nearby.mesh.ui.SosDetailDialog
import org.nearby.mesh.ui.SosTriggerButton
import org.nearby.mesh.ui.formatNodeId
import org.nearby.mesh.ui.theme.NearbyTheme
import org.nearby.mesh.ui.theme.NearbyThemeTokens

private const val PREF_ONBOARDING_COMPLETED = "pref_onboarding_completed"

class MainActivity : ComponentActivity() {

    private lateinit var identityManager: IdentityManager
    private var bleMeshEngine: BleMeshEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        identityManager = IdentityManager(this)

        val prefs = getSharedPreferences(IdentityManager.PREFS_NAME, Context.MODE_PRIVATE)
        val initialIdentity = identityManager.getOrCreateIdentity()
        val alreadyOnboarded = prefs.getBoolean(PREF_ONBOARDING_COMPLETED, false)

        val engine = BleMeshEngine(this, initialIdentity)
        bleMeshEngine = engine

        setContent {
            NearbyTheme {
                var identity by remember { mutableStateOf(initialIdentity) }
                var isOnboarded by remember { mutableStateOf(alreadyOnboarded) }
                var selectedTabIndex by remember { mutableIntStateOf(0) }
                var selectedChatPeer by remember { mutableStateOf<DiscoveredPeer?>(null) }
                var selectedSosAlert by remember { mutableStateOf<SosAlert?>(null) }
                var showSosComposeDialog by remember { mutableStateOf(false) }

                val context = LocalContext.current
                val neighbors by engine.neighbors.collectAsState()
                val activeSosAlerts by engine.activeSosAlerts.collectAsState()
                val activeTransfers by engine.activeTransfers.collectAsState()

                var hasPermissions by remember {
                    mutableStateOf(hasRequiredMeshPermissions(context))
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { results ->
                    val allGranted = results.values.all { it }
                    hasPermissions = allGranted
                    if (allGranted && isOnboarded) {
                        MeshForegroundService.start(context)
                        engine.start()
                    }
                }

                LaunchedEffect(isOnboarded) {
                    if (isOnboarded) {
                        if (hasRequiredMeshPermissions(context)) {
                            MeshForegroundService.start(context)
                            engine.start()
                        } else {
                            permissionLauncher.launch(getRequiredPermissions())
                        }
                    }
                }

                DisposableEffect(Unit) {
                    onDispose {
                        engine.stop()
                    }
                }

                if (!isOnboarded) {
                    OnboardingScreen(
                        identity = identity,
                        onContinue = { updatedName ->
                            val updated = identityManager.updateDisplayName(updatedName)
                            identity = updated
                            prefs.edit().putBoolean(PREF_ONBOARDING_COMPLETED, true).apply()
                            isOnboarded = true
                        }
                    )
                } else if (selectedChatPeer != null) {
                    val currentChatPeer = selectedChatPeer!!
                    val updatedPeer = neighbors.find { it.nodeId == currentChatPeer.nodeId } ?: currentChatPeer
                    val timeline by engine.getTimeline(currentChatPeer.nodeId).collectAsState()

                    BackHandler {
                        selectedChatPeer = null
                    }

                    PeerChatScreen(
                        peer = updatedPeer,
                        timeline = timeline,
                        audioController = engine.audioController,
                        activeTransfers = activeTransfers,
                        onSendMessage = { content ->
                            engine.sendDirectMessage(currentChatPeer.nodeId, content)
                        },
                        onSendFile = { file ->
                            engine.sendFile(currentChatPeer.nodeId, file)
                        },
                        onSendVoiceNote = { file, durationMs ->
                            engine.sendVoiceNote(currentChatPeer.nodeId, file, durationMs)
                        },
                        onCancelTransfer = { transferId ->
                            engine.cancelTransfer(transferId)
                        },
                        onBackClick = {
                            selectedChatPeer = null
                        },
                        onVerifyPeer = { peerNodeId ->
                            engine.markPeerVerified(peerNodeId)
                        },
                        onMarkUntrusted = { peerNodeId ->
                            engine.markPeerCompromised(peerNodeId)
                        }
                    )
                } else {
                    Scaffold(
                        floatingActionButton = {
                            SosTriggerButton(
                                onTriggerSos = {
                                    engine.broadcastSos(
                                        distressNote = "EMERGENCY: Assistance Needed",
                                        location = null,
                                        isMedical = false,
                                        isTrapped = false
                                    )
                                }
                            )
                        },
                        bottomBar = {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 3.dp
                            ) {
                                NavigationBarItem(
                                    selected = selectedTabIndex == 0,
                                    onClick = { selectedTabIndex = 0 },
                                    icon = { Text("📡", fontSize = 20.sp) },
                                    label = { Text("Radar") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                )
                                NavigationBarItem(
                                    selected = selectedTabIndex == 1,
                                    onClick = { selectedTabIndex = 1 },
                                    icon = { Text("👤", fontSize = 20.sp) },
                                    label = { Text("Identity") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                )
                            }
                        }
                    ) { paddingValues ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues)
                        ) {
                            SosBanner(
                                alerts = activeSosAlerts,
                                onAlertClick = { alert ->
                                    selectedSosAlert = alert
                                }
                            )

                            if (!hasPermissions) {
                                PermissionWarningBanner(
                                    onRequestPermissions = {
                                        permissionLauncher.launch(getRequiredPermissions())
                                    }
                                )
                            }

                            when (selectedTabIndex) {
                                0 -> PeerRadarScreen(
                                    localIdentity = identity,
                                    neighbors = neighbors,
                                    isScanning = hasPermissions,
                                    onPeerClick = { peer ->
                                        selectedChatPeer = peer
                                    },
                                    onVerifyPeer = { peerNodeId ->
                                        engine.markPeerVerified(peerNodeId)
                                    },
                                    onMarkCompromised = { peerNodeId ->
                                        engine.markPeerCompromised(peerNodeId)
                                    }
                                )
                                1 -> MainIdentityDashboard(
                                    identity = identity,
                                    onUpdateDisplayName = { newName ->
                                        val updated = identityManager.updateDisplayName(newName)
                                        identity = updated
                                    }
                                )
                            }
                        }
                    }
                }

                selectedSosAlert?.let { alert ->
                    SosDetailDialog(
                        alert = alert,
                        onDismissRequest = { selectedSosAlert = null },
                        onAcknowledgeOrDismiss = {
                            engine.dismissSos(alert.alertId)
                            selectedSosAlert = null
                        }
                    )
                }

                if (showSosComposeDialog) {
                    SosComposeDialog(
                        onDismiss = { showSosComposeDialog = false },
                        onBroadcast = { message, isMed, isTrap ->
                            engine.broadcastSos(
                                distressNote = message,
                                location = null,
                                isMedical = isMed,
                                isTrapped = isTrap
                            )
                            showSosComposeDialog = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionWarningBanner(onRequestPermissions: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = NearbyThemeTokens.alertColors.warning.copy(alpha = 0.15f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Bluetooth Permission Needed",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Required to discover and relay for nearby emergency peers.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onRequestPermissions,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Grant", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainIdentityDashboard(
    identity: NodeIdentity,
    onUpdateDisplayName: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var editNameInput by remember { mutableStateOf(identity.displayName) }
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copiedFeedback by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "📡", fontSize = 16.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Nearby",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Offline Emergency Mesh",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Surface(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Identity Overview Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "LOCAL IDENTITY",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = identity.displayName,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    editNameInput = identity.displayName
                                    showEditDialog = true
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Edit", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "NODE ADDRESS (FINGERPRINT)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Text(
                                text = formatNodeId(identity.nodeId),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (copiedFeedback) "Copied!" else "8-byte SHA-256 fingerprint",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (copiedFeedback) NearbyThemeTokens.alertColors.success else MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            TextButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(identity.nodeId))
                                    copiedFeedback = true
                                }
                            ) {
                                Text("Copy Address", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                // Mesh Status Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(NearbyThemeTokens.alertColors.success)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Identity & Mesh Engine Ready",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "BLE Presence discovery active. Radio transmits 8-byte cryptographic node address and pseudonym without central servers.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Security Attributes Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Text(
                            text = "SECURITY ATTRIBUTES",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        SecurityAttributeRow(icon = "🔑", label = "Key Algorithm", value = "Ed25519 (256-bit)")
                        SecurityAttributeRow(icon = "🔒", label = "Key Protection", value = "AndroidKeyStore / AES-GCM")
                        SecurityAttributeRow(icon = "📡", label = "Transport", value = "BLE Standalone Mesh")
                        SecurityAttributeRow(icon = "🚫", label = "Telemetry / PII", value = "Zero tracking / Offline")
                    }
                }
            }
        }
    }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Display Pseudonym", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    Text(
                        text = "Set a local pseudonym for other nearby mesh survivors to see.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editNameInput,
                        onValueChange = { editNameInput = it },
                        singleLine = true,
                        label = { Text("Display Name") }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = editNameInput.trim()
                        if (trimmed.isNotEmpty()) {
                            onUpdateDisplayName(trimmed)
                            showEditDialog = false
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SecurityAttributeRow(
    icon: String,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = icon, fontSize = 16.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun hasRequiredMeshPermissions(context: Context): Boolean {
    val permissions = getRequiredPermissions()
    return permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

private fun getRequiredPermissions(): Array<String> {
    val list = mutableListOf<String>()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        list.add(Manifest.permission.BLUETOOTH_SCAN)
        list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        list.add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        list.add(Manifest.permission.BLUETOOTH)
        list.add(Manifest.permission.BLUETOOTH_ADMIN)
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        list.add(Manifest.permission.POST_NOTIFICATIONS)
        list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    list.add(Manifest.permission.RECORD_AUDIO)

    return list.toTypedArray()
}