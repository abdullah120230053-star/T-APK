package com.dama.tahany.service

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.util.Size
import android.view.Surface
import android.view.WindowManager
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
import com.dama.tahany.files.MAX_TRANSFER_BYTES
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
import com.dama.tahany.screencast.ScreenCastSession
import com.dama.tahany.screenshot.ScreenshotCandidate
import com.dama.tahany.screenshot.ScreenshotLedger
import com.dama.tahany.screenshot.ScreenshotLedgerStore
import com.dama.tahany.screenshot.ScreenshotLoopGuard
import com.dama.tahany.screenshot.ScreenshotWatcher
import com.dama.tahany.util.toHex
import com.dama.tahany.util.Hashing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import java.io.File

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
    private lateinit var albumStore: ScreenshotLedgerStore
    private lateinit var albumLedger: ScreenshotLedger
    private val albumLock = Mutex()
    private val incomingLock = Mutex()
    private lateinit var loopGuard: ScreenshotLoopGuard
    private var callMonitor: CallMonitor? = null
    private lateinit var callCommandHandler: CallCommandHandler
    private lateinit var screenCast: ScreenCastSession
    private var projectionForeground = false
    private var role: DeviceRole? = null
    private var callState = CallState.IDLE

    private fun refreshFeatures() {
        watcher.refresh()
        callMonitor?.start()
    }

    inner class LocalBinder : android.os.Binder() {
        fun controller(): LinkController = link
        fun setScreenSurface(surface: Surface?) = screenCast.setSurface(surface)
        fun stopScreenCast() = screenCast.stop()
        fun sendClipboard() = clipboard.sendNow()
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
        albumStore = ScreenshotLedgerStore(this, "album_hashes")
        albumLedger = albumStore.load()
        loopGuard = ScreenshotLoopGuard(ledger)
        watcher = ScreenshotWatcher(this, ::onScreenshotCandidate)
        callCommandHandler = CallCommandHandler(this)

        screenCast = ScreenCastSession(
            link = link,
            scope = scope,
            projectionManager = getSystemService(MediaProjectionManager::class.java),
            displaySize = ::displaySize,
            densityDpi = { resources.displayMetrics.densityDpi },
            setProjectionForeground = ::setProjectionForeground,
        )

        clipboard.start()
        watcher.start()

        scope.launch {
            settings.settings.collect { current ->
                role = current.role
                if (role == DeviceRole.PHONE) {
                    if (callMonitor == null) {
                        callMonitor = CallMonitor(this@TahanyService) { callEvent ->
                            callState = callEvent.state
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
            link.state.collect { state ->
                if (state.phase != LinkPhase.CONNECTED) {
                    screenCast.clear()
                    NotificationHelper.cancelCall(this@TahanyService)
                }
                updateServiceNotification(state)
            }
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
        scope.launch(Dispatchers.IO) { stager.cleanupOlderThan() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startForegroundCompat()) {
            link.disconnect()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                link.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_DISCONNECT -> link.disconnect()
            ACTION_SEND_CLIPBOARD -> clipboard.sendNow()
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
                }
            }
            ACTION_REFRESH -> refreshFeatures()
            ACTION_START_SCREEN_CAST -> intent.projectionData()?.let(screenCast::startConsent)

            ACTION_START, null -> Unit
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        callMonitor?.stop()
        screenCast.clear()
        if (activeServiceInstance === this) activeServiceInstance = null
        runCatching { watcher.stop() }
        runCatching { clipboard.stop() }
        runCatching { link.close() }
        scope.cancel()
        NotificationHelper.cancel(this, NotificationHelper.SERVICE_NOTIFICATION_ID)
        NotificationHelper.cancelCall(this)
        super.onDestroy()
    }

    private fun setProjectionForeground(enabled: Boolean): Boolean {
        projectionForeground = enabled
        return startForegroundCompat()
    }

    private fun startForegroundCompat(): Boolean {
        val state = link.state.value
        val notification = NotificationHelper.serviceNotification(
            this,
            if (projectionForeground) "Your screen is being shared." else getString(R.string.notification_service_text),
            connected = state.phase == LinkPhase.CONNECTED,
        )
        return runCatching {
            val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    (if (projectionForeground) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
            } else {
                0
            }
            ServiceCompat.startForeground(this, NotificationHelper.SERVICE_NOTIFICATION_ID, notification, types)
        }.isSuccess.also { if (!it) projectionForeground = false }
    }

    @Suppress("DEPRECATION")
    private fun displaySize(): Size {
        val windowManager = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.maximumWindowMetrics.bounds.let { Size(it.width(), it.height()) }
        } else {
            val point = android.graphics.Point()
            windowManager.defaultDisplay.getRealSize(point)
            Size(point.x, point.y)
        }
    }

    private fun updateServiceNotification(state: LinkUiState) {
        val text = if (projectionForeground) "Your screen is being shared." else when (state.phase) {
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
                if (!link.isConnected()) break
                val staged = stager.stage(uri)
                if (staged == null) {
                    link.showMessage("Could not read one of the selected files.")
                    continue
                }
                link.sendFile(staged.file, staged.mimeType)
            }
        }
    }

    private fun onScreenshotCandidate(candidate: ScreenshotCandidate) {
        scope.launch(Dispatchers.IO) {
            if (!settings.current().autoScreenshot) return@launch
            val staged = stager.stage(candidate.uri) ?: return@launch
            try {
                val sha = Hashing.sha256(staged.file)
                saveScreenshotToAlbum(staged.file, staged.name, staged.mimeType, sha)
                withContext(Dispatchers.Main) {
                    if (!link.isConnected() || !loopGuard.claimOutgoing(candidate.relativePath, sha)) {
                        return@withContext
                    }
                    try {
                        val sent = link.sendFile(staged.file, staged.mimeType, candidate.dateAdded * 1000L, sha)
                        if (!sent) loopGuard.rollback(sha)
                        ledgerStore.save(ledger)
                    } catch (cancelled: CancellationException) {
                        loopGuard.rollback(sha)
                        throw cancelled
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                toast("Could not sync the new screenshot.")
            } finally {
                staged.file.delete()
                staged.file.parentFile?.delete()
            }
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
            is LinkEvent.NotificationReceived -> {
                NotificationHelper.notifyMirrored(this, event.event)
            }

            is LinkEvent.NotificationReplyReceived -> {
                val ok = TahanyNotificationListenerService.dispatchReply(this, event.key, event.text)
                if (!ok) link.sendReplyFailure()
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
                val action = event.command.action
                val allowed = if (action == CallAction.ANSWER) callState == CallState.RINGING
                else callState != CallState.IDLE
                link.sendCallResult(action, allowed && callCommandHandler.execute(action))
            }

            is LinkEvent.ScreenCastOfferReceived -> screenCast.acceptOffer()
            is LinkEvent.ScreenCastAnswerReceived -> screenCast.onAnswer(event.answer.accepted)
            LinkEvent.ScreenCastStopReceived -> screenCast.clear()
            is LinkEvent.ScreenStreamReceived -> screenCast.receive(event.stream)
            is LinkEvent.TouchEventReceived -> screenCast.dispatchTouch(event.touch)
        }
    }

    private fun saveIncomingFile(event: LinkEvent.FileReceived) {
        val offer = event.offer
        saveIncoming(event.uri, offer.name, offer.mimeType, offer.size, offer.sha256,
            offer.transferId, event.session, isScreenshot = false)
    }

    private fun saveIncomingScreenshot(event: LinkEvent.ScreenshotReceived) {
        val notice = event.notice
        saveIncoming(event.uri, notice.name, "", notice.size, notice.sha256,
            notice.transferId, event.session, isScreenshot = true)
    }

    private fun saveIncoming(
        uri: Uri,
        name: String,
        mimeType: String,
        size: Long,
        sha256: String,
        transferId: String,
        sourceSession: Int,
        isScreenshot: Boolean,
    ) {
        scope.launch {
            val result = incomingLock.withLock {
                withContext(Dispatchers.IO) {
                    var staged: File? = null
                    var claimed = false
                    try {
                        require(size in 0..MAX_TRANSFER_BYTES && sha256.matches(SHA256_PATTERN))
                        val target = File.createTempFile("incoming-", ".tmp", cacheDir).also { staged = it }
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        val source = if (uri.scheme == "file") File(checkNotNull(uri.path)).inputStream()
                        else checkNotNull(contentResolver.openInputStream(uri))
                        var count = 0L
                        source.use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    count += read
                                    check(count <= size) { "Size mismatch." }
                                    digest.update(buffer, 0, read)
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                        check(count == size) { "Size mismatch." }
                        check(digest.digest().toHex().equals(sha256, ignoreCase = true)) {
                            "Checksum mismatch."
                        }
                        if (isScreenshot) {
                            claimed = loopGuard.claimIncoming(sha256.lowercase())
                            if (!claimed) return@withContext Result.success(null)
                        }
                        val saved = if (isScreenshot) {
                            saveScreenshotToAlbum(target, name, mimeType, sha256.lowercase())
                        } else {
                            incomingStore.save(target, name, mimeType)
                        }
                        if (claimed) ledgerStore.save(ledger)
                        Result.success(saved)
                    } catch (cancelled: CancellationException) {
                        if (claimed) loopGuard.rollback(sha256.lowercase())
                        throw cancelled
                    } catch (_: Exception) {
                        if (claimed) loopGuard.rollback(sha256.lowercase())
                        Result.failure(IllegalStateException("Could not verify or save the received file."))
                    } finally {
                        staged?.delete()
                        link.deleteIncomingFile(uri)
                    }
                }
            }
            val saved = result.getOrNull()
            link.recordIncoming(sourceSession, transferId, name, size, result.exceptionOrNull()?.message)
            link.sendFileResult(transferId, sourceSession, saved?.displayName, result.exceptionOrNull()?.message)
            if (saved != null) {
                NotificationHelper.notifyIncoming(this@TahanyService, saved)
                toast("Saved ${saved.displayName}.")
            } else if (result.isFailure) {
                toast("Could not save the received file.")
            }
        }
    }

    private suspend fun saveScreenshotToAlbum(
        file: File,
        name: String,
        mimeType: String,
        hash: String,
    ) = albumLock.withLock {
        if (albumLedger.contains(hash)) return@withLock null
        val saved = incomingStore.save(file, name, mimeType)
        albumLedger.tryAdd(hash)
        albumStore.save(albumLedger)
        saved
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

    @Suppress("DEPRECATION")
    private fun Intent.projectionData(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            getParcelableExtra(EXTRA_PROJECTION_DATA)
        }

    companion object {
        private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
        const val ACTION_START_SCREEN_CAST = "com.dama.tahany.action.START_SCREEN_CAST"
        const val EXTRA_PROJECTION_DATA = "com.dama.tahany.extra.PROJECTION_DATA"
        const val ACTION_START = "com.dama.tahany.action.START"
        const val ACTION_STOP = "com.dama.tahany.action.STOP"
        const val ACTION_DISCONNECT = "com.dama.tahany.action.DISCONNECT"
        const val ACTION_SEND_CLIPBOARD = "com.dama.tahany.action.SEND_CLIPBOARD"
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
