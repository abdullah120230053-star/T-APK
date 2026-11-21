package com.dama.tahany.ui

import android.app.Activity
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dama.tahany.model.LinkPhase
import com.dama.tahany.permissions.PermissionCatalog

@Composable
fun TahanyScreen(viewModel: TahanyViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val share by viewModel.pendingShare.collectAsStateWithLifecycle()
    val shareDeferred by viewModel.shareDeferred.collectAsStateWithLifecycle()
    val link by viewModel.linkState.collectAsStateWithLifecycle()
    val serviceReady by viewModel.serviceReady.collectAsStateWithLifecycle()
    val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let(viewModel::startScreenCast)
        }
    }

    var permissionRefresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionRefresh += 1
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val missing = remember(permissionRefresh, settings?.role) {
        PermissionCatalog.missing(context, settings?.role)
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

    val connected = link.phase == LinkPhase.CONNECTED
    if (share != null && current.role != null && (connected || !shareDeferred)) {
        AlertDialog(
            onDismissRequest = { viewModel.finishShare(false) },
            title = { Text("Send to the other device?") },
            text = {
                Text(if (connected) {
                    "Share the selected content with ${link.peer?.name ?: "your paired device"}."
                } else {
                    "Connect your devices first. Tahany will keep this share ready."
                })
            },
            confirmButton = {
                TextButton(onClick = { if (connected) viewModel.finishShare(true) else viewModel.deferShare() }) {
                    Text(if (connected) "Send" else "Pair devices")
                }
            },
            dismissButton = { TextButton(onClick = { viewModel.finishShare(false) }) { Text("Cancel") } },
        )
    }

    when {
        current.role == null -> RoleScreen(onSelect = viewModel::selectRole)
        else -> HomeScreen(
            settings = current,
            link = link,
            missingPermissions = missing,
            onSetPackageBlocked = viewModel::setPackageBlocked,
            serviceReady = serviceReady,
            onChangeRole = viewModel::selectRole,
            onHost = viewModel::host,
            onJoin = viewModel::join,
            onConnect = viewModel::connect,
            onConfirm = viewModel::confirmPairing,
            onDisconnect = viewModel::disconnect,
            onSendClipboard = viewModel::sendClipboard,
            onSendFile = { uri -> viewModel.sendUris(listOf(uri)) },
            onRequestPermission = { item ->
                permissionLauncher.launch(PermissionCatalog.request(listOf(item)))
            },
            onRefresh = {
                viewModel.refreshPermissions()
                permissionRefresh += 1
            },
            onToggleScreenshot = viewModel::setAutoScreenshot,
            onForgetPeer = viewModel::forgetPeer,
            onAnswerCall = viewModel::answerCall,
            onDeclineCall = viewModel::declineCall,
            onToggleSpeaker = viewModel::toggleSpeaker,
            onReplyNotification = viewModel::replyNotification,
            onStartScreenCast = {
                projectionManager?.let { manager ->
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    } else {
                        manager.createScreenCaptureIntent()
                    }
                    projectionLauncher.launch(intent)
                }
            },
            onScreenSurface = viewModel::setScreenSurface,
            onScreenTouch = viewModel::sendScreenTouch,
            onStopScreenCast = viewModel::stopScreenCast,
        )
    }
}
