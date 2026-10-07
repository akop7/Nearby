package org.nearby.mesh.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.nearby.mesh.domain.AttachmentType
import org.nearby.mesh.domain.AudioController
import org.nearby.mesh.domain.DeliveryStatus
import org.nearby.mesh.domain.DiscoveredPeer
import org.nearby.mesh.domain.PeerTrustState
import org.nearby.mesh.domain.TextMessage
import org.nearby.mesh.domain.TransferProgress
import org.nearby.mesh.domain.TransferStatus
import org.nearby.mesh.ui.theme.NearbyThemeTokens
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private object NoOpAudioController : AudioController {
    override val isRecording: Boolean = false
    override val isPlaying: Boolean = false
    override val currentPlayingPath: String? = null
    override fun startRecording(outputFile: File): Boolean = false
    override fun stopRecording(): Long = 0L
    override fun cancelRecording() {}
    override fun playVoiceNote(filePath: String, onComplete: () -> Unit): Boolean = false
    override fun pauseVoiceNote() {}
    override fun stopVoiceNote() {}
}

/**
 * 1-on-1 End-to-End Encrypted Peer Chat Screen (Phase 4 & 5).
 * Supports text messaging, high-bandwidth file transfers, and voice notes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerChatScreen(
    peer: DiscoveredPeer,
    timeline: List<TextMessage>,
    onSendMessage: (String) -> Unit,
    onBackClick: () -> Unit,
    onVerifyPeer: (String) -> Unit,
    onMarkUntrusted: (String) -> Unit,
    modifier: Modifier = Modifier,
    audioController: AudioController? = null,
    activeTransfers: Map<String, TransferProgress> = emptyMap(),
    onSendFile: (File) -> Unit = {},
    onSendVoiceNote: (File, Long) -> Unit = { _, _ -> },
    onCancelTransfer: (String) -> Unit = {}
) {
    val controller = audioController ?: NoOpAudioController
    var messageInput by remember { mutableStateOf("") }
    var showVerificationDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // Auto-scroll to bottom when new messages arrive or are sent
    LaunchedEffect(timeline.size) {
        if (timeline.isNotEmpty()) {
            listState.animateScrollToItem(timeline.size - 1)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            controller.stopVoiceNote()
            controller.cancelRecording()
        }
    }

    Scaffold(
        modifier = modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = peer.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(modifier = Modifier.clickable { showVerificationDialog = true }) {
                                    TrustBadge(
                                        trustState = peer.trustState,
                                        isEncrypted = peer.isEncrypted
                                    )
                                }
                            }
                            Text(
                                text = formatNodeId(peer.nodeId),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Security shield button for out-of-band SAS code verification
                        val isSessionEncrypted = peer.trustState == PeerTrustState.ENCRYPTED || peer.trustState == PeerTrustState.VERIFIED
                        IconButton(
                            onClick = { showVerificationDialog = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text(
                                text = if (peer.trustState == PeerTrustState.VERIFIED) "🛡️" else if (isSessionEncrypted) "🔐" else "⏳",
                                fontSize = 18.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Text("←", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            val isSessionEncrypted = peer.trustState == PeerTrustState.ENCRYPTED || peer.trustState == PeerTrustState.VERIFIED
            ChatInputBar(
                text = messageInput,
                audioController = controller,
                onTextChanged = { messageInput = it },
                onSend = {
                    if (isSessionEncrypted && messageInput.isNotBlank()) {
                        onSendMessage(messageInput.trim())
                        messageInput = ""
                    }
                },
                onSendFile = onSendFile,
                onSendVoiceNote = onSendVoiceNote,
                isEnabled = isSessionEncrypted
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            val isSessionEncrypted = peer.trustState == PeerTrustState.ENCRYPTED || peer.trustState == PeerTrustState.VERIFIED

            // Compromised or warning header if untrusted
            if (peer.trustState == PeerTrustState.COMPROMISED) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = NearbyThemeTokens.alertColors.sosContainerLight
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚠️", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Warning: This peer is marked untrusted. Messages may be intercepted or forged.",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = NearbyThemeTokens.alertColors.sos
                        )
                    }
                }
            } else if (!isSessionEncrypted) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⏳", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Completing secure handshake...",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (peer.trustState == PeerTrustState.ENCRYPTED) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clickable { showVerificationDialog = true },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🔒", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Encrypted with forward secrecy. Tap to verify 6-digit SAS code.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (timeline.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("💬", fontSize = 24.sp)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Secure Mesh Chat",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Messages, voice notes, and files are routed peer-to-peer with Noise end-to-end encryption.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(timeline, key = { it.messageId }) { message ->
                        ChatMessageBubble(
                            message = message,
                            audioController = controller,
                            activeTransfer = message.transferId?.let { activeTransfers[it] },
                            onCancelTransfer = onCancelTransfer
                        )
                    }
                }
            }
        }
    }

    if (showVerificationDialog) {
        PeerVerificationDialog(
            peer = peer,
            onDismiss = { showVerificationDialog = false },
            onConfirmVerified = {
                onVerifyPeer(peer.nodeId)
                showVerificationDialog = false
            },
            onMarkUntrusted = {
                onMarkUntrusted(peer.nodeId)
                showVerificationDialog = false
            }
        )
    }
}

/**
 * Renders individual chat items: text bubble, voice note player bubble, or file transfer card.
 */
@Composable
private fun ChatMessageBubble(
    message: TextMessage,
    audioController: AudioController,
    activeTransfer: TransferProgress?,
    onCancelTransfer: (String) -> Unit
) {
    val timeFormatted = remember(message.timestampMs) {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        sdf.format(Date(message.timestampMs))
    }

    val bubbleAlignment = if (message.isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleShape = if (message.isOutgoing) {
        RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 2.dp)
    } else {
        RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 2.dp, bottomEnd = 14.dp)
    }

    val containerColor = if (message.isOutgoing) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val contentColor = if (message.isOutgoing) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = bubbleAlignment
    ) {
        Column(
            horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Surface(
                shape = bubbleShape,
                color = containerColor,
                shadowElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    when (message.attachmentType) {
                        AttachmentType.VOICE_NOTE -> {
                            VoiceNoteBubbleContent(
                                message = message,
                                audioController = audioController,
                                contentColor = contentColor
                            )
                        }
                        AttachmentType.FILE -> {
                            FileCardContent(
                                message = message,
                                activeTransfer = activeTransfer,
                                onCancelTransfer = onCancelTransfer,
                                contentColor = contentColor
                            )
                        }
                        AttachmentType.NONE -> {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = contentColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        if (!message.isOutgoing && message.hopCount > 0) {
                            Text(
                                text = "${message.hopCount}h • ",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = contentColor.copy(alpha = 0.7f)
                            )
                        }

                        Text(
                            text = timeFormatted,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = contentColor.copy(alpha = 0.7f)
                        )

                        if (message.isOutgoing) {
                            Spacer(modifier = Modifier.width(4.dp))
                            val statusIcon = when (message.deliveryStatus) {
                                DeliveryStatus.SENDING -> "⏳"
                                DeliveryStatus.SENT -> "✓"
                                DeliveryStatus.RELAYED -> "✓✓"
                                DeliveryStatus.DELIVERED -> "✓✓"
                                DeliveryStatus.FAILED -> "⚠️"
                            }
                            Text(
                                text = statusIcon,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = contentColor.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Playable voice note component with play/pause toggle and duration indicator.
 */
@Composable
private fun VoiceNoteBubbleContent(
    message: TextMessage,
    audioController: AudioController,
    contentColor: Color
) {
    val filePath = message.attachmentPath
    val isCurrentPlaying = audioController.currentPlayingPath == filePath && audioController.isPlaying
    var isLocalPlaying by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        IconButton(
            onClick = {
                if (filePath.isNullOrBlank()) return@IconButton
                if (isLocalPlaying || isCurrentPlaying) {
                    audioController.pauseVoiceNote()
                    isLocalPlaying = false
                } else {
                    val played = audioController.playVoiceNote(filePath) {
                        isLocalPlaying = false
                    }
                    isLocalPlaying = played
                }
            },
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(contentColor.copy(alpha = 0.15f))
        ) {
            Text(
                text = if (isLocalPlaying || isCurrentPlaying) "⏸" else "▶",
                fontSize = 16.sp,
                color = contentColor
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Voice Note",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor
            )
            Text(
                text = formatDuration(message.attachmentDurationMs),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.75f)
            )
        }

        Text(
            text = "🎙️",
            fontSize = 18.sp
        )
    }
}

/**
 * File transfer card showing filename, formatted size, and real-time progress bar.
 */
@Composable
private fun FileCardContent(
    message: TextMessage,
    activeTransfer: TransferProgress?,
    onCancelTransfer: (String) -> Unit,
    contentColor: Color
) {
    val isTransferring = activeTransfer != null && !activeTransfer.isFinished
    val transferProgress = activeTransfer?.progressFraction ?: 0f

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(contentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text("📁", fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message.attachmentName ?: message.content,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatFileSize(message.attachmentSizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.75f)
                )
            }

            if (isTransferring && message.transferId != null) {
                IconButton(
                    onClick = { onCancelTransfer(message.transferId) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Text("✕", fontSize = 14.sp, color = contentColor)
                }
            }
        }

        if (isTransferring) {
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { transferProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = contentColor,
                trackColor = contentColor.copy(alpha = 0.2f),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${(transferProgress * 100).toInt()}% • High-speed Wi-Fi Direct",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = contentColor.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Interactive input composer bar supporting text input, attachment picking,
 * and push-to-record voice notes.
 */
@Composable
private fun ChatInputBar(
    text: String,
    audioController: AudioController,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onSendFile: (File) -> Unit,
    onSendVoiceNote: (File, Long) -> Unit,
    isEnabled: Boolean = true
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRecording by remember { mutableStateOf(false) }
    var recordingDurationSeconds by remember { mutableIntStateOf(0) }
    var recordingFile by remember { mutableStateOf<File?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val copiedFile = copyUriToCache(context, uri)
            if (copiedFile != null) {
                onSendFile(copiedFile)
            }
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingDurationSeconds = 0
            while (isActive && isRecording) {
                delay(1000L)
                recordingDurationSeconds++
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isRecording) {
                // Live voice recording control UI
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔴", fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Recording ${formatDuration(recordingDurationSeconds * 1000L)}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Cancel recording button
                IconButton(
                    onClick = {
                        audioController.cancelRecording()
                        isRecording = false
                        recordingFile?.delete()
                        recordingFile = null
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Text("🗑️", fontSize = 18.sp)
                }

                // Finish & Send voice note button
                Button(
                    onClick = {
                        val durationMs = audioController.stopRecording()
                        isRecording = false
                        val file = recordingFile
                        if (file != null && durationMs > 500L) {
                            onSendVoiceNote(file, durationMs)
                        }
                        recordingFile = null
                    },
                    shape = CircleShape,
                    modifier = Modifier.size(44.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("✓", fontSize = 18.sp)
                }
            } else {
                // Attachment picker button
                IconButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    enabled = isEnabled,
                    modifier = Modifier.size(42.dp)
                ) {
                    Text(
                        "📎",
                        fontSize = 20.sp,
                        color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChanged,
                    placeholder = {
                        Text(
                            text = if (isEnabled) "Encrypted message..." else "Completing secure handshake...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    enabled = isEnabled,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        disabledBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    ),
                    maxLines = 4
                )

                Spacer(modifier = Modifier.width(8.dp))

                if (text.isNotBlank()) {
                    // Send text message button
                    Button(
                        onClick = onSend,
                        enabled = isEnabled,
                        shape = CircleShape,
                        modifier = Modifier.size(48.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                        )
                    ) {
                        Text("➤", fontSize = 18.sp)
                    }
                } else {
                    // Start voice note recording button
                    IconButton(
                        onClick = {
                            val audioDir = File(context.cacheDir, "voice_notes").apply { mkdirs() }
                            val tempFile = File(audioDir, "vn_${System.currentTimeMillis()}.m4a")
                            val started = audioController.startRecording(tempFile)
                            if (started) {
                                recordingFile = tempFile
                                isRecording = true
                            }
                        },
                        enabled = isEnabled,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                            )
                    ) {
                        Text(
                            "🎙️",
                            fontSize = 20.sp,
                            color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Copies a selected content URI to the application's temporary cache directory.
 */
private fun copyUriToCache(context: Context, uri: Uri): File? {
    return try {
        val contentResolver = context.contentResolver
        var fileName = "attachment_${System.currentTimeMillis()}"

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                val queriedName = cursor.getString(nameIndex)
                if (!queriedName.isNullOrBlank()) {
                    fileName = queriedName
                }
            }
        }

        val cacheDir = File(context.cacheDir, "pending_transfers").apply { mkdirs() }
        val outputFile = File(cacheDir, fileName)

        contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(outputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        outputFile
    } catch (e: Exception) {
        null
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.getDefault(), "%.1f MB", mb)
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}
