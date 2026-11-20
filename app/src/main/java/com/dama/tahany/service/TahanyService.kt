package com.dama.tahany.service

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.ServiceCompat
import com.dama.tahany.R
import com.dama.tahany.calls.CallCommandHandler
import com.dama.tahany.calls.CallMonitor
import com.dama.tahany.clipboard.ClipboardSyncManager
import com.dama.tahany.data.SettingsRepository
import com.dama.tahany.files.IncomingFileStore
import com.dama.tahany.files.OutgoingFileStager
import com.dama.tahany.link.LinkController
import com.dama.tahany.link.LinkEvent
import com.dama.tahany.model.DeviceRole
import com.dama.tahany.model.LinkPhase
import com.dama.tahany.model.LinkUiState
import com.dama.tahany.notifications.TahanyNotificationListenerService
import com.dama.tahany.protocol.CallAction
import com.dama.tahany.protocol.CallState
import com.dama.tahany.protocol.TahanyMessage
import com.dama.tahany.screencast.TahanyAccessibilityService
import com.dama.tahany.screenshot.ScreenshotCandidate
import com.dama.tahany.screenshot.ScreenshotLedger
import com.dama.tahany.screenshot.ScreenshotLedgerStore
import com.dama.tahany.screenshot.ScreenshotLoopGuard
import com.dama.tahany.screenshot.ScreenshotWatcher
import com.dama.tahany.util.FileNames
import com.dama.tahany.util.Hashing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class TahanyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var settings: SettingsRepository
    private lateinit var link: LinkController
    private lateinit var clipboard: ClipboardSyncManager
    private lateinit var watcher: ScreenshotWatcher
    private lateinit var incomingStore: IncomingFileStore
    private lateinit var stager: OutgoingFileStager
    private lateinit var ledgerStore: ScreenshotLedgerStore
    private lateinit var ledger: ScreenshotLedger
    private lateinit var loopGuard: ScreenshotLoopGuard
    private var callMonitor: CallMonitor? = null
    private lateinit var callCommandHandler: CallCommandHandler

    inner class LocalBinder : android.os.Binder() {
        fun controller(): LinkController = link
    }

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
        NotificationHelper.createChannels(this)

        settings = SettingsRepository(this)
        link = LinkController(this, settings, scope)
        clipboard = ClipboardSyncManager(this, scope, link)
        incomingStore = IncomingFileStore(this)
        stager = OutgoingFileStager(this)
        ledgerStore = ScreenshotLedgerStore(this)
        ledger = ledgerStore.load()
        loopGuard = ScreenshotLoopGuard(ledger)
        watcher = ScreenshotWatcher(this, ::onScreenshotCandidate)
        callCommandHandler = CallCommandHandler(this)

        if (!startForegroundCompat()) return

        clipboard.start()
        watcher.start()

        scope.launch {
            settings.settings.collect { current ->
                if (current.role == DeviceRole.PHONE) {
                    if (callMonitor == null) {
                        callMonitor = CallMonitor(this@TahanyService) { callEvent ->
                            link.sendCallEvent(callEvent)
                        }
                        callMonitor?.start()
                    }
                } else {
                    callMonitor?.stop()
                    callMonitor = null
                }
            }
        }

        scope.launch {
            link.state.collect { state -> updateServiceNotification(state) }
        }
        scope.launch {
            link.events.collect { event -> handleLinkEvent(event) }
        }
        scope.launch {
            settings.settings.collect { current ->
                if (current.role == null) {
                    updateServiceText("Choose Phone or Tablet in Tahany.")
                }
            }
        }
        stager.cleanupOlderThan()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::link.isInitialized) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_DISCONNECT -> link.disconnect()
            ACTION_SEND_CLIPBOARD -> clipboard.sendNow()
            ACTION_SEND_TEXT -> link.sendClipboard(intent.getStringExtra(EXTRA_TEXT).orEmpty())
            ACTION_SEND_URIS -> sendUris(intent.uris())
            ACTION_COPY_TEXT -> copyText(intent.getStringExtra(EXTRA_TEXT))
            ACTION_SEND_NOTIFICATION_REPLY -> {
                val key = intent.getStringExtra(EXTRA_NOTIFICATION_KEY).orEmpty()
                val text = intent.getStringExtra(EXTRA_REPLY_TEXT).orEmpty()
                if (key.isNotEmpty() && text.isNotEmpty()) {
                    link.sendNotificationReply(key, text)
                }
            }
            ACTION_SEND_CALL_COMMAND -> {
                val actionStr = intent.getStringExtra(EXTRA_CALL_ACTION).orEmpty()
                val action = runCatching { CallAction.valueOf(actionStr) }.getOrNull()
                if (action != null) {
                    link.sendCallCommand(action)
                    if (action == CallAction.SPEAKER_ON) link.updateSpeakerState(true)
                    else if (action == CallAction.SPEAKER_OFF) link.updateSpeakerState(false)
                }
            }
            ACTION_REFRESH -> {
                watcher.refresh()
                startForegroundCompat()
            }

            ACTION_START, null -> Unit
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        callMonitor?.stop()
        if (activeServiceInstance === this) activeServiceInstance = null
        runCatching { watcher.stop() }
        runCatching { clipboard.stop() }
        runCatching { link.close() }
        scope.cancel()
        NotificationHelper.cancel(this, NotificationHelper.SERVICE_NOTIFICATION_ID)
        NotificationHelper.cancelCall(this)
        super.onDestroy()
    }

    private fun startForegroundCompat(): Boolean {
        val notification = NotificationHelper.serviceNotification(
            this,
            getString(R.string.notification_service_text),
            connected = false,
        )
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NotificationHelper.SERVICE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, notification)
            }
            true
        } catch (_: SecurityException) {
            // On Android 14+ the connectedDevice type needs a granted Bluetooth
            // runtime permission. Start untyped so the user can reach the app
            // and grant it; ACTION_REFRESH upgrades the type afterwards.
            try {
                ServiceCompat.startForeground(
                    this,
                    NotificationHelper.SERVICE_NOTIFICATION_ID,
                    notification,
                    0,
                )
                true
            } catch (_: Exception) {
                stopSelf()
                false
            }
        } catch (_: Exception) {
            stopSelf()
            false
        }
    }

    private fun updateServiceNotification(state: LinkUiState) {
        val text = when (state.phase) {
            LinkPhase.IDLE -> "Ready. Host or join a session."
            LinkPhase.ADVERTISING -> "Waiting for the other device to join…"
            LinkPhase.DISCOVERING -> "Looking for a Tahany device…"
            LinkPhase.HANDSHAKE -> "Link open. Compare the six-digit code."
            LinkPhase.CONNECTED -> "Connected to ${state.peer?.name ?: "the other device"}."
            LinkPhase.ERROR -> state.lastError ?: "The link stopped. Open Tahany to retry."
        }
        NotificationHelper.notifyService(this, text, state.phase == LinkPhase.CONNECTED)
    }

    private fun updateServiceText(text: String) {
        NotificationHelper.notifyService(this, text, connected = false)
    }

    private fun sendUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            if (settings.current().role == null) {
                toast("Choose Phone or Tablet first.")
                return@launch
            }
            if (!link.isConnected()) {
                toast("Host or join a session before sharing a file.")
                return@launch
            }
            for (uri in uris) {
                val staged = stager.stage(uri) ?: continue
                link.sendFile(staged.file, staged.mimeType)
                delay(200L)
            }
        }
    }

    private fun onScreenshotCandidate(candidate: ScreenshotCandidate) {
        scope.launch(Dispatchers.IO) {
            if (!settings.current().autoScreenshot || !link.isConnected()) return@launch
            val sha = try {
                contentResolver.openInputStream(candidate.uri)?.use { Hashing.sha256(it) }
            } catch (_: Exception) {
                null
            } ?: return@launch
            if (!loopGuard.claimOutgoing(candidate.relativePath, sha)) return@launch
            ledgerStore.save(ledger)

            val directory = File(cacheDir, "screenshots/${UUID.randomUUID()}").apply { mkdirs() }
            val target = File(directory, FileNames.sanitize(candidate.displayName))
            try {
                contentResolver.openInputStream(candidate.uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("No screenshot stream")
            } catch (_: Exception) {
                loopGuard.rollback(sha)
                ledgerStore.save(ledger)
                target.delete()
                return@launch
            }
            link.sendScreenshot(
                file = target,
                name = candidate.displayName,
                sha256 = sha,
                size = target.length(),
                takenAt = candidate.dateAdded * 1000L,
            )
            ledgerStore.save(ledger)
        }
    }

    private fun handleLinkEvent(event: LinkEvent) {
        when (event) {
            is LinkEvent.ClipboardReceived -> {
                clipboard.applyRemote(event.text)
                toast("Clipboard received.")
            }

            is LinkEvent.FileReceived -> saveIncomingFile(event)
            is LinkEvent.ScreenshotReceived -> saveIncomingScreenshot(event)
            is LinkEvent.Notice -> updateServiceNotification(link.state.value)
            is LinkEvent.Problem -> toast(event.message)

            is LinkEvent.NotificationReceived -> {
                NotificationHelper.notifyMirrored(this, event.event)
            }

            is LinkEvent.NotificationReplyReceived -> {
                val ok = TahanyNotificationListenerService.dispatchReply(this, event.key, event.text)
                if (ok) {
                    toast("Sent reply.")
                } else {
                    toast("Could not send reply for ${event.key}.")
                }
            }

            is LinkEvent.CallEventReceived -> {
                if (event.event.state == CallState.RINGING) {
                    NotificationHelper.notifyIncomingCall(
                        this,
                        event.event,
                        link.state.value.activeCall?.isSpeakerOn ?: false,
                    )
                } else {
                    NotificationHelper.cancelCall(this)
                }
            }

            is LinkEvent.CallCommandReceived -> {
                callCommandHandler.execute(event.command.action)
            }

            is LinkEvent.ScreenCastOfferReceived -> {
                link.sendScreenCastAnswer(true)
                toast("Incoming screen stream.")
            }

            is LinkEvent.ScreenCastAnswerReceived -> {
                if (event.answer.accepted) {
                    toast("Screen sharing accepted by tablet.")
                } else {
                    toast("Screen sharing declined: ${event.answer.reason ?: "cancelled"}")
                }
            }

            is LinkEvent.ScreenCastStopReceived -> {
                toast("Screen sharing stopped.")
            }

            is LinkEvent.TouchEventReceived -> {
                TahanyAccessibilityService.injectTouch(event.touch)
            }
        }
    }

    private fun saveIncomingFile(event: LinkEvent.FileReceived) {
        scope.launch(Dispatchers.IO) {
            val offer = event.offer
            val computed = runCatching { Hashing.sha256(event.file) }.getOrNull()
            if (computed == null) {
                link.sendFileResult(offer?.transferId, false, null, "Could not read the received file")
                event.file.delete()
                return@launch
            }
            if (offer?.sha256 != null && !offer.sha256.equals(computed, ignoreCase = true)) {
                link.sendFileResult(offer.transferId, false, null, "Checksum mismatch")
                event.file.delete()
                toast("Rejected ${offer.name}: checksum mismatch.")
                return@launch
            }
            if (offer != null && offer.size > 0 && event.size != offer.size) {
                link.sendFileResult(offer.transferId, false, null, "Size mismatch")
                event.file.delete()
                toast("Rejected ${offer.name}: incomplete transfer.")
                return@launch
            }
            try {
                val saved = incomingStore.save(
                    file = event.file,
                    requestedName = offer?.name ?: event.file.name,
                    mimeType = offer?.mimeType ?: "application/octet-stream",
                )
                link.sendFileResult(offer?.transferId, true, saved.displayName, null)
                NotificationHelper.notifyIncoming(this@TahanyService, saved)
                toast("Saved ${saved.displayName}.")
            } catch (error: Exception) {
                link.sendFileResult(offer?.transferId, false, null, error.message)
                toast("Could not save the received file.")
            } finally {
                event.file.delete()
            }
        }
    }

    private fun saveIncomingScreenshot(event: LinkEvent.ScreenshotReceived) {
        scope.launch(Dispatchers.IO) {
            val computed = runCatching { Hashing.sha256(event.file) }.getOrNull()
            if (computed == null || !computed.equals(event.sha256, ignoreCase = true)) {
                loopGuard.rollback(event.sha256)
                event.file.delete()
                toast("Rejected a screenshot: checksum mismatch.")
                return@launch
            }
            if (!loopGuard.claimIncoming(event.sha256)) {
                event.file.delete()
                return@launch
            }
            try {
                val saved = incomingStore.save(
                    file = event.file,
                    requestedName = event.name,
                    mimeType = "image/*",
                )
                ledgerStore.save(ledger)
                NotificationHelper.notifyIncoming(this@TahanyService, saved)
                toast("Screenshot saved to Pictures/Tahany.")
            } catch (_: Exception) {
                loopGuard.rollback(event.sha256)
                toast("Could not save the received screenshot.")
            } finally {
                event.file.delete()
            }
        }
    }

    private fun copyText(text: String?) {
        if (text.isNullOrBlank()) return
        val clipboardManager = getSystemService(ClipboardManager::class.java) ?: return
        clipboardManager.setPrimaryClip(ClipData.newPlainText("Tahany", text))
        toast("Copied.")
    }

    private fun toast(message: String) {
        mainHandler.post { Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() }
    }

    @Suppress("DEPRECATION")
    private fun Intent.uris(): List<Uri> =
        getParcelableArrayListExtra<Uri>(EXTRA_URIS).orEmpty()

    companion object {
        const val ACTION_START = "com.dama.tahany.action.START"
        const val ACTION_STOP = "com.dama.tahany.action.STOP"
        const val ACTION_DISCONNECT = "com.dama.tahany.action.DISCONNECT"
        const val ACTION_SEND_CLIPBOARD = "com.dama.tahany.action.SEND_CLIPBOARD"
        const val ACTION_SEND_TEXT = "com.dama.tahany.action.SEND_TEXT"
        const val ACTION_SEND_URIS = "com.dama.tahany.action.SEND_URIS"
        const val ACTION_COPY_TEXT = "com.dama.tahany.action.COPY_TEXT"
        const val ACTION_REFRESH = "com.dama.tahany.action.REFRESH"
        const val ACTION_SEND_NOTIFICATION_REPLY = "com.dama.tahany.action.SEND_NOTIFICATION_REPLY"
        const val ACTION_SEND_CALL_COMMAND = "com.dama.tahany.action.SEND_CALL_COMMAND"
        const val EXTRA_URIS = "com.dama.tahany.extra.URIS"
        const val EXTRA_TEXT = "com.dama.tahany.extra.TEXT"
        const val EXTRA_NOTIFICATION_KEY = "com.dama.tahany.extra.NOTIFICATION_KEY"
        const val EXTRA_REPLY_TEXT = "com.dama.tahany.extra.REPLY_TEXT"
        const val EXTRA_CALL_ACTION = "com.dama.tahany.extra.CALL_ACTION"

        private var activeServiceInstance: TahanyService? = null

        fun forwardNotification(event: TahanyMessage.NotificationEvent) {
            activeServiceInstance?.link?.sendNotification(event)
        }
    }
}
