package com.dama.tahany.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dama.tahany.model.AppSettings
import com.dama.tahany.model.DeviceRole
import com.dama.tahany.model.LinkPhase
import com.dama.tahany.model.LinkUiState
import com.dama.tahany.permissions.PermissionCatalog
import com.dama.tahany.permissions.PermissionItem
import com.dama.tahany.protocol.CallState

private enum class DashboardPage { LINK, ACTIVITY, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    settings: AppSettings,
    link: LinkUiState,
    missingPermissions: List<PermissionItem>,
    canPair: Boolean,
    onGrantLinkPermissions: () -> Unit,
    onSetPackageBlocked: (String, Boolean) -> Unit,
    nearbyAvailable: Boolean,
    serviceReady: Boolean,
    onChangeRole: (DeviceRole) -> Unit,
    onHost: () -> Unit,
    onJoin: () -> Unit,
    onConnect: (String, String) -> Unit,
    onConfirm: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    onSendClipboard: () -> Unit,
    onSendFile: (Uri) -> Unit,
    onRequestPermission: (PermissionItem) -> Unit,
    onRefresh: () -> Unit,
    onToggleScreenshot: (Boolean) -> Unit,
    onForgetPeer: (String) -> Unit,
    onAnswerCall: () -> Unit = {},
    onDeclineCall: () -> Unit = {},
    onToggleSpeaker: () -> Unit = {},
    onReplyNotification: (String, String) -> Unit = { _, _ -> },
    onStartScreenCast: () -> Unit,
    onStopScreenCast: () -> Unit,
    onScreenSurface: (android.view.Surface?) -> Unit,
    onScreenTouch: (Float, Float, Float, Float, Int) -> Unit,
) {
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onSendFile(uri)
    }

    var page by rememberSaveable { mutableStateOf(DashboardPage.LINK) }
    val connected = link.phase == LinkPhase.CONNECTED
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(page, link.message) {
        val message = link.message
        if (page == DashboardPage.ACTIVITY && connected && message != null && !message.startsWith("Connected")) {
            snackbar.showSnackbar(message)
        }
    }
    LaunchedEffect(link.phase, link.activeCall?.state, link.screenCast.isReceiving) {
        if (link.phase == LinkPhase.HANDSHAKE || link.screenCast.isReceiving ||
            link.activeCall?.state == CallState.RINGING
        ) page = DashboardPage.LINK
    }
    BackHandler(page != DashboardPage.LINK) { page = DashboardPage.LINK }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Tahany", fontWeight = FontWeight.Bold) },
                actions = {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.padding(end = 16.dp),
                    ) {
                        Text(
                            if (settings.role == DeviceRole.PHONE) "Phone" else "Tablet",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar {
                DashboardPage.entries.forEach { destination ->
                    val (label, icon) = when (destination) {
                        DashboardPage.LINK -> "Link" to Icons.Filled.Home
                        DashboardPage.ACTIVITY -> "Activity" to Icons.Filled.Info
                        DashboardPage.SETTINGS -> "Settings" to Icons.Filled.Settings
                    }
                    NavigationBarItem(
                        selected = page == destination,
                        onClick = { page = destination },
                        label = { Text(label) },
                        icon = { Icon(icon, contentDescription = null) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            key(page) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (page) {
                        DashboardPage.LINK -> {
                            link.activeCall?.let { call ->
                                item { ActiveCallCard(call, onAnswerCall, onDeclineCall, onToggleSpeaker) }
                            }
                            if (link.phase == LinkPhase.IDLE || link.phase == LinkPhase.ERROR) {
                                item {
                                    DeviceIllustration(
                                        Modifier.fillMaxWidth(),
                                        "Pair your devices",
                                        "Open Tahany on your phone and tablet.",
                                        compact = true,
                                    )
                                }
                            }
                            if (!nearbyAvailable) {
                                item {
                                    MessageCard(
                                        "Google Play services required",
                                        "Update or enable Google Play services on this device to use Nearby Connections.",
                                    )
                                }
                            }
                            if (!canPair && !connected && nearbyAvailable) {
                                item { MissingPermissionsCard(onGrantLinkPermissions) }
                            }
                            item {
                                LinkCard(
                                    link, onHost, onJoin, onConnect, onConfirm, onDisconnect,
                                    canPair = nearbyAvailable && serviceReady && canPair,
                                )
                            }
                            if (connected) {
                                item {
                                    ScreenCastCard(
                                        settings.role, link.screenCast, onStartScreenCast, onStopScreenCast,
                                        onScreenSurface, onScreenTouch,
                                    )
                                }
                                item {
                                    QuickActionsCard(onSendClipboard) { filePicker.launch(arrayOf("*/*")) }
                                }
                            }
                            link.transfer?.let { item { TransferCard(it) } }
                        }
                        DashboardPage.ACTIVITY -> {
                            item { Text("Activity", style = MaterialTheme.typography.headlineMedium) }
                            link.transfer?.let { item { TransferCard(it) } }
                            if (settings.role == DeviceRole.TABLET) {
                                item { MirroredNotificationsCard(link.mirroredNotifications, onReplyNotification) }
                            }
                            if (settings.role == DeviceRole.PHONE && link.transfer == null) {
                                item { MessageCard("No recent transfers", "Files and screenshots will appear here.") }
                            }
                        }
                        DashboardPage.SETTINGS -> {
                            item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }
                            item { SettingsCard(settings, onToggleScreenshot, onForgetPeer, onChangeRole) }
                            item {
                                PermissionsCard(
                                    settings.role, missingPermissions, onRequestPermission, onRefresh,
                                )
                            }
                            if (settings.role == DeviceRole.PHONE) {
                                item { NotificationFilterCard(settings.blockedPackages, onSetPackageBlocked) }
                            }
                            item { KeepAliveCard() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageCard(title: String, detail: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermissionsCard(
    role: DeviceRole?,
    missing: List<PermissionItem>,
    onRequest: (PermissionItem) -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Permissions", style = MaterialTheme.typography.titleMedium)
            missing.filterNot(PermissionCatalog::isForLink).forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.bodyLarge)
                        Text(item.why, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onRequest(item) }) { Text("Allow") }
                }
            }
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)))
            }) { Text("App permissions") }
            if (role == DeviceRole.PHONE) {
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                    Text("Notification access")
                }
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                    Text("Accessibility for remote touch")
                }
            }
            TextButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Refresh permissions")
            }
        }
    }
}
