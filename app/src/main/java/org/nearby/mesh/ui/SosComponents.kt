package org.nearby.mesh.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nearby.mesh.domain.SosAlert
import org.nearby.mesh.ui.theme.NearbyThemeTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Top banner displayed when active incoming emergency SOS alerts are received on the mesh.
 * Styled with high-contrast alert red (Design.md §2.3) and fully tappable for details.
 */
@Composable
fun SosBanner(
    alerts: List<SosAlert>,
    onAlertClick: (SosAlert) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = alerts.isNotEmpty(),
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        val latestAlert = alerts.firstOrNull() ?: return@AnimatedVisibility

        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clickable { onAlertClick(latestAlert) },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = NearbyThemeTokens.alertColors.sos
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🚨", fontSize = 18.sp)
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "EMERGENCY SOS (${alerts.size})",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp
                                ),
                                color = NearbyThemeTokens.alertColors.onSos
                            )
                            if (latestAlert.hopCount > 0) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${latestAlert.hopCount} hop(s)",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = NearbyThemeTokens.alertColors.onSos.copy(alpha = 0.8f)
                                )
                            }
                        }

                        Text(
                            text = "${latestAlert.senderDisplayName}: ${latestAlert.distressMessage}",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = NearbyThemeTokens.alertColors.onSos,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.25f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "VIEW",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = NearbyThemeTokens.alertColors.onSos
                    )
                }
            }
        }
    }
}

/**
 * Emergency SOS action button with a 2-second hold-to-activate mechanism (PRD §5.3, Design.md §2.3).
 * Prevents accidental triggering while providing clear visual circular progress feedback.
 */
@Composable
fun SosTriggerButton(
    onTriggerSos: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPressing by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableFloatStateOf(0f) }
    var triggeredThisPress by remember { mutableStateOf(false) }

    LaunchedEffect(isPressing) {
        if (isPressing) {
            triggeredThisPress = false
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(durationMillis = 2000, easing = LinearEasing)
            ) { value, _ ->
                holdProgress = value
                if (value >= 1f && !triggeredThisPress) {
                    triggeredThisPress = true
                    onTriggerSos()
                }
            }
        } else {
            holdProgress = 0f
            triggeredThisPress = false
        }
    }

    Box(
        modifier = modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(NearbyThemeTokens.alertColors.sos)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressing = true
                        try {
                            awaitRelease()
                        } finally {
                            isPressing = false
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Background track
        CircularProgressIndicator(
            progress = { 1f },
            modifier = Modifier.size(60.dp),
            color = Color.White.copy(alpha = 0.2f),
            strokeWidth = 3.dp
        )

        // Progress ring showing 2-second hold progression
        if (isPressing && holdProgress > 0f) {
            CircularProgressIndicator(
                progress = { holdProgress },
                modifier = Modifier.size(60.dp),
                color = Color.White,
                strokeWidth = 3.5.dp,
                strokeCap = StrokeCap.Round
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "SOS",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                ),
                color = NearbyThemeTokens.alertColors.onSos
            )
            Text(
                text = if (isPressing) "${((1f - holdProgress) * 2f).coerceAtLeast(0f).toInt() + 1}s" else "HOLD",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = NearbyThemeTokens.alertColors.onSos.copy(alpha = 0.85f)
            )
        }
    }
}

/**
 * Modal dialog for composing and immediately broadcasting an emergency SOS alert.
 */
@Composable
fun SosComposeDialog(
    onDismiss: () -> Unit,
    onBroadcast: (distressMessage: String, isMedical: Boolean, isTrapped: Boolean) -> Unit
) {
    var distressMessage by remember { mutableStateOf("EMERGENCY: Need immediate assistance") }
    var isMedical by remember { mutableStateOf(false) }
    var isTrapped by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🚨", fontSize = 20.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Broadcast Emergency SOS",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = NearbyThemeTokens.alertColors.sos
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "This unencrypted emergency beacon will flood across all nearby peer nodes over multi-hop mesh with highest priority.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = distressMessage,
                    onValueChange = { if (it.length <= 120) distressMessage = it },
                    label = { Text("Distress Message") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    supportingText = {
                        Text("${distressMessage.length}/120 characters")
                    }
                )

                Text(
                    text = "Distress Tags:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = isMedical,
                        onClick = { isMedical = !isMedical },
                        label = { Text("🏥 Medical") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NearbyThemeTokens.alertColors.sosContainerLight,
                            selectedLabelColor = NearbyThemeTokens.alertColors.sos
                        )
                    )

                    FilterChip(
                        selected = isTrapped,
                        onClick = { isTrapped = !isTrapped },
                        label = { Text("🚪 Trapped") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NearbyThemeTokens.alertColors.sosContainerLight,
                            selectedLabelColor = NearbyThemeTokens.alertColors.sos
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onBroadcast(distressMessage.trim(), isMedical, isTrapped)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NearbyThemeTokens.alertColors.sos
                )
            ) {
                Text("Broadcast SOS Beacon", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Detailed view of an incoming SOS distress alert.
 */
@Composable
fun SosDetailDialog(
    alert: SosAlert,
    onDismissRequest: () -> Unit,
    onAcknowledgeOrDismiss: () -> Unit
) {
    val timeFormatted = remember(alert.timestampMs) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        sdf.format(Date(alert.timestampMs))
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🚨", fontSize = 22.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "EMERGENCY ALERT",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = NearbyThemeTokens.alertColors.sos
                    )
                    Text(
                        text = "Received at $timeFormatted",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Sender info box
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = alert.senderDisplayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "🔋 ${alert.batteryPercent}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Node ID: ${formatNodeId(alert.senderNodeId)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (alert.hopCount > 0) {
                            Text(
                                text = "Relayed via ${alert.hopCount} mesh hop(s)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Distress message box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(NearbyThemeTokens.alertColors.sosContainerLight)
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = "DISTRESS MESSAGE:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = NearbyThemeTokens.alertColors.sos
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = alert.distressMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = NearbyThemeTokens.alertColors.sos
                        )
                    }
                }

                // Tags
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (alert.isMedicalEmergency) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(NearbyThemeTokens.alertColors.sos.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "🏥 MEDICAL",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = NearbyThemeTokens.alertColors.sos
                            )
                        }
                    }
                    if (alert.isTrapped) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(NearbyThemeTokens.alertColors.sos.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "🚪 TRAPPED",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = NearbyThemeTokens.alertColors.sos
                            )
                        }
                    }
                }

                // GPS Location coordinates if available
                if (alert.location != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "📍 GPS COORDINATES",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Lat: ${alert.location.latitude}, Lng: ${alert.location.longitude}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                fontWeight = FontWeight.Medium
                            )
                            if (alert.location.accuracyMeters != null) {
                                Text(
                                    text = "Accuracy: ±${alert.location.accuracyMeters}m",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAcknowledgeOrDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Dismiss Alert")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Close")
            }
        }
    )
}
