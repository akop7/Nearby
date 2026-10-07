package org.nearby.mesh.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nearby.mesh.crypto.NodeIdentity
import org.nearby.mesh.domain.DiscoveredPeer
import org.nearby.mesh.domain.PeerTrustState
import org.nearby.mesh.ui.theme.NearbyThemeTokens
import kotlin.math.cos
import kotlin.math.sin

/**
 * Peer Radar screen visualizing discovered nearby mesh peers in real-time
 * via an animated radar canvas, signal indicators, and cryptographic trust badges (PRD §5.2, Design.md).
 */
@Composable
fun PeerRadarScreen(
    localIdentity: NodeIdentity,
    neighbors: List<DiscoveredPeer>,
    modifier: Modifier = Modifier,
    isScanning: Boolean = true,
    onPeerClick: (DiscoveredPeer) -> Unit = {},
    onVerifyPeer: (String) -> Unit = {},
    onMarkCompromised: (String) -> Unit = {}
) {
    var selectedPeerForVerification by remember { mutableStateOf<DiscoveredPeer?>(null) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Radar Visualizer Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                RadarCanvas(
                    neighbors = neighbors,
                    isScanning = isScanning,
                    modifier = Modifier.fillMaxSize()
                )

                // Center local node badge
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(2.dp, MaterialTheme.colorScheme.onPrimary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "YOU",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            // Radar Status Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (isScanning) NearbyThemeTokens.alertColors.success
                                else NearbyThemeTokens.alertColors.unverifiedPeer
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isScanning) "RADAR SCANNING" else "SCANNER IDLE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "${neighbors.size} PEERS IN RANGE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            // Neighbor List
            if (neighbors.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "📡",
                            fontSize = 40.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Listening for nearby peers...",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Ensure other devices have Bluetooth turned on with Nearby open.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(neighbors, key = { it.nodeId }) { neighbor ->
                        NeighborItemCard(
                            neighbor = neighbor,
                            onClick = { onPeerClick(neighbor) },
                            onSecurityClick = { selectedPeerForVerification = neighbor }
                        )
                    }
                }
            }
        }
    }

    selectedPeerForVerification?.let { peer ->
        PeerVerificationDialog(
            peer = peer,
            onDismiss = { selectedPeerForVerification = null },
            onConfirmVerified = {
                onVerifyPeer(peer.nodeId)
                selectedPeerForVerification = null
            },
            onMarkUntrusted = {
                onMarkCompromised(peer.nodeId)
                selectedPeerForVerification = null
            }
        )
    }
}

@Composable
private fun RadarCanvas(
    neighbors: List<DiscoveredPeer>,
    isScanning: Boolean,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val alertColors = NearbyThemeTokens.alertColors
    val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val radarSweepBrush = Brush.sweepGradient(
        listOf(
            Color.Transparent,
            primaryColor.copy(alpha = 0.03f),
            primaryColor.copy(alpha = 0.25f)
        )
    )

    val infiniteTransition = rememberInfiniteTransition(label = "RadarSweep")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "SweepAngle"
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = (minOf(size.width, size.height) / 2f) - 12f

        // Draw 3 concentric distance range rings
        val ringCount = 3
        for (i in 1..ringCount) {
            val radius = maxRadius * (i.toFloat() / ringCount)
            drawCircle(
                color = outlineColor,
                radius = radius,
                center = center,
                style = Stroke(width = 1.5f)
            )
        }

        // Draw crosshairs
        drawLine(
            color = outlineColor,
            start = Offset(center.x - maxRadius, center.y),
            end = Offset(center.x + maxRadius, center.y),
            strokeWidth = 1f
        )
        drawLine(
            color = outlineColor,
            start = Offset(center.x, center.y - maxRadius),
            end = Offset(center.x, center.y + maxRadius),
            strokeWidth = 1f
        )

        // Draw animated sweep beam
        if (isScanning) {
            drawArc(
                brush = radarSweepBrush,
                startAngle = sweepAngle - 60f,
                sweepAngle = 60f,
                useCenter = true,
                topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
                size = androidx.compose.ui.geometry.Size(maxRadius * 2f, maxRadius * 2f)
            )
        }

        // Plot neighbors as blips on radar rings based on RSSI
        neighbors.forEach { neighbor ->
            val normalizedDistance = ((-neighbor.rssi - 40).toFloat() / 60f).coerceIn(0.2f, 0.95f)
            val radius = maxRadius * normalizedDistance

            val angleDeg = (neighbor.nodeId.hashCode().toDouble().let { Math.abs(it) % 360.0 })
            val angleRad = Math.toRadians(angleDeg)

            val blipX = center.x + (radius * cos(angleRad)).toFloat()
            val blipY = center.y + (radius * sin(angleRad)).toFloat()

            val blipColor = when (neighbor.trustState) {
                PeerTrustState.VERIFIED -> alertColors.success
                PeerTrustState.COMPROMISED -> alertColors.sos
                PeerTrustState.ENCRYPTED -> primaryColor
                PeerTrustState.UNVERIFIED -> alertColors.unverifiedPeer
            }

            drawCircle(
                color = blipColor.copy(alpha = 0.35f),
                radius = 8f,
                center = Offset(blipX, blipY)
            )
            drawCircle(
                color = blipColor,
                radius = 4f,
                center = Offset(blipX, blipY)
            )
        }
    }
}

@Composable
private fun NeighborItemCard(
    neighbor: DiscoveredPeer,
    onClick: () -> Unit,
    onSecurityClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (neighbor.trustState) {
                            PeerTrustState.VERIFIED -> "🛡️"
                            PeerTrustState.COMPROMISED -> "⚠️"
                            PeerTrustState.ENCRYPTED -> "🔐"
                            PeerTrustState.UNVERIFIED -> "👤"
                        },
                        fontSize = 18.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = neighbor.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(modifier = Modifier.clickable(onClick = onSecurityClick)) {
                            TrustBadge(trustState = neighbor.trustState, isEncrypted = neighbor.isEncrypted)
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formatNodeId(neighbor.nodeId),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                val signalText = "${neighbor.rssi} dBm"
                val signalColor = when {
                    neighbor.rssi >= -65 -> NearbyThemeTokens.alertColors.success
                    neighbor.rssi >= -80 -> NearbyThemeTokens.alertColors.warning
                    else -> NearbyThemeTokens.alertColors.unverifiedPeer
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(signalColor.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = signalText,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = signalColor
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Text("💬", fontSize = 16.sp)
                }

                Spacer(modifier = Modifier.height(4.dp))

                val secondsAgo = ((System.currentTimeMillis() - neighbor.lastSeenMs) / 1000L).coerceAtLeast(0)
                val lastSeenText = if (secondsAgo < 5) "Active now" else "${secondsAgo}s ago"

                Text(
                    text = lastSeenText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun TrustBadge(
    trustState: PeerTrustState,
    isEncrypted: Boolean
) {
    val (text, color) = when (trustState) {
        PeerTrustState.VERIFIED -> Pair("VERIFIED ✓", NearbyThemeTokens.alertColors.success)
        PeerTrustState.COMPROMISED -> Pair("UNTRUSTED ⚠", NearbyThemeTokens.alertColors.sos)
        PeerTrustState.ENCRYPTED -> Pair("ENCRYPTED 🔒", MaterialTheme.colorScheme.primary)
        PeerTrustState.UNVERIFIED -> if (isEncrypted) {
            Pair("ENCRYPTED 🔒", MaterialTheme.colorScheme.primary)
        } else {
            Pair("HANDSHAKE", NearbyThemeTokens.alertColors.unverifiedPeer)
        }
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            ),
            color = color
        )
    }
}

@Composable
fun PeerVerificationDialog(
    peer: DiscoveredPeer,
    onDismiss: () -> Unit,
    onConfirmVerified: () -> Unit,
    onMarkUntrusted: () -> Unit
) {
    val sas = peer.sasCode ?: "------"
    val formattedSas = if (sas.length == 6) "${sas.take(3)} ${sas.takeLast(3)}" else sas

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Peer Security Verification",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Verify identity for ${peer.displayName}:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // 6-digit SAS code card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "SHORT AUTHENTICATION STRING (SAS)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = formattedSas,
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Text(
                    text = "Compare this 6-digit SAS code with the peer out-of-band (e.g. in person or voice call). Matching numbers confirm the session is protected against Man-in-the-Middle attacks.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmVerified,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NearbyThemeTokens.alertColors.success
                )
            ) {
                Text("Confirm Match (Verify)")
            }
        },
        dismissButton = {
            Row {
                if (peer.trustState == PeerTrustState.VERIFIED) {
                    TextButton(onClick = onMarkUntrusted) {
                        Text("Mark Untrusted", color = NearbyThemeTokens.alertColors.sos)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
}
