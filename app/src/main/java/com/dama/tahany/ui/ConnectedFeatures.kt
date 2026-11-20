package com.dama.tahany.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dama.tahany.model.ActiveCallState
import com.dama.tahany.model.DeviceRole
import com.dama.tahany.model.MirroredNotification
import com.dama.tahany.model.ScreenCastPhase
import com.dama.tahany.model.ScreenCastState
import com.dama.tahany.protocol.CallState

@Composable
internal fun ActiveCallCard(
    call: ActiveCallState,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onToggleSpeaker: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Phone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = call.contactName ?: call.number ?: "Incoming Phone Call",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Call on phone • Audio stays on phone or headset",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (call.state == CallState.RINGING) {
                    Button(
                        onClick = onAnswer,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E7D32),
                        ),
                    ) {
                        Text("Answer")
                    }
                }
                Button(
                    onClick = onDecline,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(if (call.state == CallState.RINGING) "Decline" else "End call")
                }
                OutlinedButton(onClick = onToggleSpeaker) {
                    Text(if (call.isSpeakerOn) "Speaker Off" else "Speaker On")
                }
            }
        }
    }
}

@Composable
internal fun MirroredNotificationsCard(
    notifications: List<MirroredNotification>,
    onReply: (String, String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Phone Notifications",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (notifications.isEmpty()) {
                Text("No mirrored notifications yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (notif in notifications) {
                key(notif.key) {
                    var replyText by rememberSaveable(notif.key) { mutableStateOf("") }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = notif.appLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = notif.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (notif.text.isNotBlank()) {
                                Text(
                                    text = notif.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (notif.hasReply) {
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = replyText,
                                        onValueChange = { replyText = it.take(2_000) },
                                        placeholder = { Text("Type reply…", style = MaterialTheme.typography.bodySmall) },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            if (replyText.isNotBlank()) {
                                                onReply(notif.key, replyText)
                                                replyText = ""
                                            }
                                        },
                                        enabled = replyText.isNotBlank(),
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send Reply", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ScreenCastCard(
    role: DeviceRole?,
    state: ScreenCastState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSurface: (android.view.Surface?) -> Unit,
    onTouch: (Float, Float, Float, Float, Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Screen mirroring", style = MaterialTheme.typography.titleMedium)
            val info = when (state.phase) {
                ScreenCastPhase.ERROR -> state.error ?: "Screen mirroring stopped."
                ScreenCastPhase.STARTING -> "Starting the screen stream…"
                ScreenCastPhase.STREAMING -> "Your phone screen is visible on the tablet."
                ScreenCastPhase.RECEIVING -> "Live from your phone."
                ScreenCastPhase.IDLE -> if (role == DeviceRole.PHONE) {
                    "Show your phone screen on the tablet."
                } else {
                    "Start sharing from the phone to view its screen here."
                }
            }
            Text(info, style = MaterialTheme.typography.bodyMedium)
            if (state.isReceiving) {
                ScreenCastSurface(state.width, state.height, onSurface, onTouch)
            }
            if (state.isReceiving || state.isSharing) {
                OutlinedButton(onClick = onStop) { Text("Stop mirroring") }
            } else if (role == DeviceRole.PHONE) {
                Button(onClick = onStart) { Text("Share screen") }
            }
        }
    }
}
