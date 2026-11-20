package com.dama.tahany.ui

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dama.tahany.model.AppSettings
import com.dama.tahany.model.DeviceRole
import com.dama.tahany.permissions.GuideStep
import com.dama.tahany.permissions.OemGuide

@Composable
internal fun SettingsCard(
    settings: AppSettings,
    onToggleScreenshot: (Boolean) -> Unit,
    onForgetPeer: (String) -> Unit,
    onChangeRole: (DeviceRole) -> Unit,
) {
    var confirmRoleChange by rememberSaveable { mutableStateOf(false) }
    val otherRole = if (settings.role == DeviceRole.PHONE) DeviceRole.TABLET else DeviceRole.PHONE
    val otherRoleName = if (otherRole == DeviceRole.PHONE) "Phone" else "Tablet"
    if (confirmRoleChange) {
        AlertDialog(
            onDismissRequest = { confirmRoleChange = false },
            title = { Text("Switch to $otherRoleName?") },
            text = { Text("Switching roles ends your current link.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRoleChange = false
                    onChangeRole(otherRole)
                }) { Text("Switch role") }
            },
            dismissButton = { TextButton(onClick = { confirmRoleChange = false }) { Text("Cancel") } },
        )
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Device preferences", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            SwitchRow(
                title = "Screenshot sync",
                body = "Collect new screenshots in Pictures/Tahany and send them to your connected device.",
                checked = settings.autoScreenshot,
                onCheckedChange = onToggleScreenshot,
            )
            HorizontalDivider()
            Text("This device", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(settings.deviceName.ifBlank { "Tahany device" }, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { confirmRoleChange = true }) { Text("Switch to $otherRoleName") }
            HorizontalDivider()
            Text("Previously paired", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (settings.trustedPeers.isEmpty()) {
                Text(
                    "Paired devices will appear here. Compare a fresh code whenever you connect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                settings.trustedPeers.forEach { peer ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(peer.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { onForgetPeer(peer.deviceId) }) { Text("Forget") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun NotificationFilterCard(blockedPackages: Set<String>, onBlock: (String, Boolean) -> Unit) {
    var packageName by rememberSaveable { mutableStateOf("") }
    val packageId = packageName.trim()
    val validPackage = packageId.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Notification privacy", style = MaterialTheme.typography.titleMedium)
            Text("Block apps whose notifications should stay on the phone.",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = packageName,
                onValueChange = { packageName = it.take(160) },
                label = { Text("Android package name") },
                placeholder = { Text("com.example.app") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            TextButton(
                enabled = validPackage && packageId !in blockedPackages,
                onClick = {
                    onBlock(packageId, true)
                    packageName = ""
                },
            ) { Text("Block app") }
            blockedPackages.sorted().forEach { blocked ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(blocked, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onBlock(blocked, false) }) { Text("Unblock") }
                }
            }
        }
    }
}

@Composable
internal fun KeepAliveCard() {
    val context = LocalContext.current
    val steps = remember { OemGuide.steps(context) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Keep the link alive",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "Adjust these settings if your device drops the link with the screen off.",
                style = MaterialTheme.typography.bodySmall,
            )
            steps.take(3).forEach { step ->
                GuideStepRow(step) { OemGuide.open(context, step) }
            }
        }
    }
}

@Composable
private fun GuideStepRow(step: GuideStep, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(6.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(step.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                step.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpen, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("Open settings")
            }
        }
    }
}
