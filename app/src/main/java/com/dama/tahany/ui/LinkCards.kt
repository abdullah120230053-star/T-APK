package com.dama.tahany.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dama.tahany.model.FoundPeer
import com.dama.tahany.model.LinkPhase
import com.dama.tahany.model.LinkUiState
import com.dama.tahany.model.TransferDirection
import com.dama.tahany.model.TransferState
import com.dama.tahany.model.TransferStatus

@Composable
internal fun MissingPermissionsCard(onGrant: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Nearby access needed", fontWeight = FontWeight.SemiBold)
                Text(
                    "Allow access to pair these devices.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onGrant) { Text("Grant") }
        }
    }
}

@Composable
internal fun LinkCard(
    link: LinkUiState,
    onHost: () -> Unit,
    onJoin: () -> Unit,
    onConnect: (String, String) -> Unit,
    onConfirm: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    canPair: Boolean,
) {
    val container = when (link.phase) {
        LinkPhase.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
        LinkPhase.ERROR -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surface
    }
    Card(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(link.phase)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = phaseTitle(link.phase),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = link.message ?: phaseSubtitle(link),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when (link.phase) {
                LinkPhase.IDLE, LinkPhase.ERROR -> {
                    if (link.phase == LinkPhase.ERROR && link.lastError != null) {
                        Text(
                            link.lastError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onHost, modifier = Modifier.weight(1f), enabled = canPair) {
                            Text("Host")
                        }
                        OutlinedButton(onClick = onJoin, modifier = Modifier.weight(1f), enabled = canPair) {
                            Text("Join")
                        }
                    }

                }

                LinkPhase.ADVERTISING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "Waiting for the other device. Keep this screen open for the first pairing.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onDisconnect) { Text("Stop hosting") }
                }

                LinkPhase.DISCOVERING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (link.foundPeers.isEmpty()) {
                        Text(
                            "Looking for a host on this Wi-Fi or Bluetooth…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            "Choose the other device:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        link.foundPeers.forEach { peer ->
                            FoundPeerRow(peer, onConnect)
                        }
                    }
                    TextButton(onClick = onDisconnect) { Text("Stop searching") }
                }

                LinkPhase.HANDSHAKE -> {
                    val sas = link.sas
                    if (sas != null && link.awaitingConfirmation) {
                        Text(
                            "Compare this code with the other screen",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = sas,
                            style = MaterialTheme.typography.displayMedium,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 6.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { onConfirm(true) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Codes match")
                            }
                            OutlinedButton(
                                onClick = { onConfirm(false) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("They don't")
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Completing the encrypted handshake…")
                        }
                    }
                    TextButton(onClick = onDisconnect) { Text("Cancel") }
                }

                LinkPhase.CONNECTED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Confirmed link to ${link.peer?.name ?: "the other device"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) {
                            Text("Disconnect")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FoundPeerRow(peer: FoundPeer, onConnect: (String, String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onConnect(peer.endpointId, peer.name) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Phone, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(peer.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Connect", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun QuickActionsCard(onSendClipboard: () -> Unit, onPickFile: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Send now", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onSendClipboard, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Clipboard")
                }
                OutlinedButton(onClick = onPickFile, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("File")
                }
            }
            Text(
                "Clipboard sync works while Tahany is open.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun TransferCard(transfer: TransferState) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    transfer.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when (transfer.status) {
                        TransferStatus.RUNNING -> if (transfer.direction == TransferDirection.INCOMING) "Receiving" else "Sending"
                        TransferStatus.SUCCEEDED -> "Done"
                        TransferStatus.FAILED -> "Failed"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (transfer.status == TransferStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            LinearProgressIndicator(
                progress = { transfer.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = if (transfer.totalBytes > 0) {
                    "${formatBytes(transfer.bytesTransferred)} of ${formatBytes(transfer.totalBytes)}"
                } else {
                    "Preparing…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            transfer.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun StatusDot(phase: LinkPhase) {
    val color = when (phase) {
        LinkPhase.CONNECTED -> MaterialTheme.colorScheme.primary
        LinkPhase.ERROR -> MaterialTheme.colorScheme.error
        LinkPhase.IDLE -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.tertiary
    }
    Box(
        Modifier
            .size(12.dp)
            .background(color, CircleShape),
    )
}

private fun phaseTitle(phase: LinkPhase): String = when (phase) {
    LinkPhase.IDLE -> "Not connected"
    LinkPhase.ADVERTISING -> "Hosting"
    LinkPhase.DISCOVERING -> "Searching"
    LinkPhase.HANDSHAKE -> "Confirm the code"
    LinkPhase.CONNECTED -> "Connected"
    LinkPhase.ERROR -> "Link stopped"
}

private fun phaseSubtitle(link: LinkUiState): String = when (link.phase) {
    LinkPhase.IDLE -> "Host on one device and join from the other."
    LinkPhase.ADVERTISING -> "Visible to the other device."
    LinkPhase.DISCOVERING -> "Looking for a host."
    LinkPhase.HANDSHAKE -> "The encrypted channel is open; confirm the code."
    LinkPhase.CONNECTED -> "Files, clipboard, and screenshots can move now."
    LinkPhase.ERROR -> "Reconnect when both devices are ready."
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(java.util.Locale.ROOT, "%.1f MB", mb)
}
