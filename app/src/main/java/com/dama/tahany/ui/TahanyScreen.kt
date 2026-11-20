package com.dama.tahany.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dama.tahany.model.AppSettings
import com.dama.tahany.model.DeviceRole
import com.dama.tahany.model.FoundPeer
import com.dama.tahany.model.LinkPhase
import com.dama.tahany.model.LinkUiState
import com.dama.tahany.model.TransferState
import com.dama.tahany.model.TransferStatus
import com.dama.tahany.permissions.GuideStep
import com.dama.tahany.permissions.OemGuide
import com.dama.tahany.permissions.PermissionCatalog
import com.dama.tahany.permissions.PermissionItem

@Composable
fun TahanyScreen(viewModel: TahanyViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val link by viewModel.linkState.collectAsStateWithLifecycle()

    var permissionRefresh by remember { mutableIntStateOf(0) }
    var skippedPermissions by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRefresh += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val missing = remember(permissionRefresh, settings?.role) {
        PermissionCatalog.missing(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshPermissions()
        permissionRefresh += 1
    }

    val current = settings
    if (current == null) {
        LoadingScreen()
        return
    }

    when {
        current.role == null -> RoleScreen(onSelect = viewModel::selectRole)
        missing.isNotEmpty() && !skippedPermissions -> PermissionsScreen(
            role = current.role,
            missing = missing,
            onGrant = { permissionLauncher.launch(it.toTypedArray()) },
            onSkip = { skippedPermissions = true },
        )

        else -> HomeScreen(
            settings = current,
            link = link,
            missingPermissions = missing.size,
            onHost = viewModel::host,
            onJoin = viewModel::join,
            onConnect = viewModel::connect,
            onConfirm = viewModel::confirmPairing,
            onDisconnect = viewModel::disconnect,
            onSendClipboard = viewModel::sendClipboard,
            onSendFile = { uri -> viewModel.sendUris(listOf(uri)) },
            onGrantPermissions = {
                permissionLauncher.launch(missing.map { it.permission }.toTypedArray())
            },
            onRefresh = viewModel::refreshPermissions,
            onToggleScreenshot = viewModel::setAutoScreenshot,
            onToggleWifiOnly = viewModel::setWifiOnly,
            onForgetPeer = viewModel::forgetPeer,
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun RoleScreen(onSelect: (DeviceRole) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Tahany",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "One private link between your phone and tablet.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Text(
            text = "What is this device?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        RoleCard(
            icon = { Icon(Icons.Filled.Phone, contentDescription = null) },
            title = "This is the phone",
            body = "Keeps the link alive, watches notifications and calls, and can share its screen later.",
            onClick = { onSelect(DeviceRole.PHONE) },
        )
        Spacer(Modifier.height(12.dp))
        RoleCard(
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            title = "This is the tablet",
            body = "Receives screenshots and files, shows notifications, and can view the phone screen.",
            onClick = { onSelect(DeviceRole.TABLET) },
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "The same app runs on both devices. You can change the role later.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RoleCard(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides
                        MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    icon()
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PermissionsScreen(
    role: DeviceRole,
    missing: List<PermissionItem>,
    onGrant: (List<String>) -> Unit,
    onSkip: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "A few permissions",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = PermissionCatalog.roleHint(role),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    missing.forEach { permission ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    permission.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    permission.why,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Button(
                onClick = { onGrant(missing.map { it.permission }) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Grant permissions")
            }
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text("Not now — I will grant them later")
            }
            Text(
                text = "Tahany asks only for what the selected role needs. It does not collect analytics " +
                    "or upload your files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    settings: AppSettings,
    link: LinkUiState,
    missingPermissions: Int,
    onHost: () -> Unit,
    onJoin: () -> Unit,
    onConnect: (String, String) -> Unit,
    onConfirm: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    onSendClipboard: () -> Unit,
    onSendFile: (Uri) -> Unit,
    onGrantPermissions: () -> Unit,
    onRefresh: () -> Unit,
    onToggleScreenshot: (Boolean) -> Unit,
    onToggleWifiOnly: (Boolean) -> Unit,
    onForgetPeer: (String) -> Unit,
) {
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onSendFile(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Tahany", fontWeight = FontWeight.Bold)
                        Text(
                            text = if (settings.role == DeviceRole.PHONE) "Phone mode" else "Tablet mode",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh permissions")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (missingPermissions > 0) {
                item {
                    MissingPermissionsCard(missingPermissions, onGrantPermissions)
                }
            }

            item {
                LinkCard(
                    link = link,
                    onHost = onHost,
                    onJoin = onJoin,
                    onConnect = onConnect,
                    onConfirm = onConfirm,
                    onDisconnect = onDisconnect,
                )
            }

            if (link.phase == LinkPhase.CONNECTED) {
                item {
                    QuickActionsCard(
                        onSendClipboard = onSendClipboard,
                        onPickFile = { filePicker.launch(arrayOf("*/*")) },
                    )
                }
            }

            link.transfer?.let { transfer ->
                item { TransferCard(transfer) }
            }

            item {
                SettingsCard(
                    settings = settings,
                    onToggleScreenshot = onToggleScreenshot,
                    onToggleWifiOnly = onToggleWifiOnly,
                    onForgetPeer = onForgetPeer,
                )
            }

            item {
                KeepAliveCard()
            }

            item {
                Text(
                    text = "Tahany links only devices that both run the app. Files and screenshots are saved " +
                        "locally in Pictures/Tahany or Downloads/Tahany. No account, no cloud.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun MissingPermissionsCard(count: Int, onGrant: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("$count permissions still needed", fontWeight = FontWeight.SemiBold)
                Text(
                    "Nearby discovery and screenshot sync will not work until they are granted.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onGrant) { Text("Grant") }
        }
    }
}

@Composable
private fun LinkCard(
    link: LinkUiState,
    onHost: () -> Unit,
    onJoin: () -> Unit,
    onConnect: (String, String) -> Unit,
    onConfirm: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
) {
    val container = when (link.phase) {
        LinkPhase.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
        LinkPhase.ERROR -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surface
    }
    Card(colors = CardDefaults.cardColors(containerColor = container)) {
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
                        Button(onClick = onHost, modifier = Modifier.weight(1f)) {
                            Text("Host")
                        }
                        OutlinedButton(onClick = onJoin, modifier = Modifier.weight(1f)) {
                            Text("Join")
                        }
                    }
                    Text(
                        "Host on one device, then join from the other. Both screens show the same " +
                            "six-digit code before any file moves.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
private fun QuickActionsCard(onSendClipboard: () -> Unit, onPickFile: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Send now", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onSendClipboard, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Send, contentDescription = null)
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
                "Clipboard auto-syncs only while Tahany is in the foreground. The quick-settings tile " +
                    "sends it without opening the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransferCard(transfer: TransferState) {
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
                        TransferStatus.RUNNING -> "Sending"
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
private fun SettingsCard(
    settings: AppSettings,
    onToggleScreenshot: (Boolean) -> Unit,
    onToggleWifiOnly: (Boolean) -> Unit,
    onForgetPeer: (String) -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            SwitchRow(
                title = "Screenshot sync",
                body = "Send new screenshots both ways and save them in Pictures/Tahany.",
                checked = settings.autoScreenshot,
                onCheckedChange = onToggleScreenshot,
            )
            HorizontalDivider()
            SwitchRow(
                title = "Prefer Wi-Fi",
                body = "A stored preference. Nearby still chooses the route in v1; do not treat this as " +
                    "a hard Wi-Fi-only guarantee yet.",
                checked = settings.wifiOnly,
                onCheckedChange = onToggleWifiOnly,
            )
            HorizontalDivider()
            Text("This device", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(settings.deviceName.ifBlank { "Tahany device" }, style = MaterialTheme.typography.bodyMedium)
            Text(
                "App ID: ${settings.deviceId.take(8)}…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Text("Trusted peers", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (settings.trustedPeers.isEmpty()) {
                Text(
                    "No trusted devices yet. Confirm a code once to remember the other device.",
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
private fun KeepAliveCard() {
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
                "Xiaomi, Redmi, POCO, and Honor battery managers can stop a background link silently. " +
                    "These settings are the difference between a demo and a working app.",
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
    LinkPhase.ERROR -> link.lastError ?: "Try again."
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(java.util.Locale.ROOT, "%.1f MB", mb)
}
