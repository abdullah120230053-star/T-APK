package com.dama.tahany.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.view.Surface
import com.dama.tahany.link.LinkController
import com.dama.tahany.protocol.CallAction
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dama.tahany.data.SettingsRepository
import com.dama.tahany.model.AppSettings
import com.dama.tahany.model.DeviceRole
import com.dama.tahany.model.LinkUiState
import com.dama.tahany.service.TahanyService
import com.dama.tahany.service.TahanyService.LocalBinder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class TahanyViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    val pendingShare = savedStateHandle.getStateFlow<Intent?>("pending_share", null)
    val shareDeferred = savedStateHandle.getStateFlow("share_deferred", false)

    fun deferShare() {
        savedStateHandle["share_deferred"] = true
    }
    private val settingsRepository = SettingsRepository(application)
    private val controller = MutableStateFlow<LinkController?>(null)
    private var serviceBinder: LocalBinder? = null
    private val ready = MutableStateFlow(false)
    val serviceReady: StateFlow<Boolean> = ready

    val settings: StateFlow<AppSettings?> = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        null,
    )

    val linkState: StateFlow<LinkUiState> = controller
        .flatMapLatest { it?.state ?: flowOf(LinkUiState()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LinkUiState())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? LocalBinder ?: return
            serviceBinder = binder
            controller.value = binder.controller()
            ready.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBinder = null
            controller.value = null
            ready.value = false
        }
    }

    init {
        val app = application
        val intent = Intent(app, TahanyService::class.java).setAction(TahanyService.ACTION_START)
        try {
            ContextCompat.startForegroundService(app, intent)
        } catch (_: Exception) {
            // The activity is still allowed to bind; it will show the error
            // state if the service cannot start in this process state.
        }
        app.bindService(
            Intent(app, TahanyService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    fun selectRole(role: DeviceRole) {
        viewModelScope.launch {
            if (settings.value?.role != role) controller.value?.disconnect()
            settingsRepository.setRole(role)
        }
    }

    fun completeSetup() {
        viewModelScope.launch { settingsRepository.completeSetup() }
    }

    fun setPackageBlocked(packageName: String, blocked: Boolean) {
        viewModelScope.launch { settingsRepository.setPackageBlocked(packageName, blocked) }
    }

    fun setAutoScreenshot(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoScreenshot(enabled) }
    }

    fun forgetPeer(deviceId: String) {
        viewModelScope.launch { settingsRepository.forgetPeer(deviceId) }
    }

    fun host() {
        controller.value?.host()
    }

    fun join() {
        controller.value?.join()
    }

    fun connect(endpointId: String, name: String) {
        controller.value?.connect(endpointId, name)
    }

    fun confirmPairing(accept: Boolean) {
        controller.value?.confirmPairing(accept)
    }

    fun disconnect() {
        controller.value?.disconnect()
    }

    fun answerCall() {
        controller.value?.sendCallCommand(CallAction.ANSWER)
    }

    fun declineCall() {
        controller.value?.sendCallCommand(CallAction.DECLINE)
    }

    fun toggleSpeaker() {
        val currentSpeaker = linkState.value.activeCall?.isSpeakerOn ?: false
        val action = if (currentSpeaker) {
            CallAction.SPEAKER_OFF
        } else {
            CallAction.SPEAKER_ON
        }
        controller.value?.sendCallCommand(action)
    }

    fun replyNotification(key: String, text: String) {
        controller.value?.sendNotificationReply(key, text)
    }

    fun startScreenCast(data: Intent) {
        val app = getApplication<Application>()
        val intent = Intent(app, TahanyService::class.java)
            .setAction(TahanyService.ACTION_START_SCREEN_CAST)
            .putExtra(TahanyService.EXTRA_PROJECTION_DATA, data)
        runCatching { ContextCompat.startForegroundService(app, intent) }.onFailure {
            controller.value?.showMessage("Android could not start screen capture.")
        }
    }

    fun stopScreenCast() = serviceBinder?.stopScreenCast() ?: Unit

    fun setScreenSurface(surface: Surface?) {
        serviceBinder?.setScreenSurface(surface)
    }

    fun sendScreenTouch(x: Float, y: Float, endX: Float, endY: Float, action: Int) {
        controller.value?.sendTouchEvent(x, y, endX, endY, action)
    }

    fun sendClipboard() {
        serviceBinder?.sendClipboard()
    }

    fun reviewIncomingShare(uris: List<Uri>, text: String?) {
        if (uris.isEmpty() && text.isNullOrBlank()) return
        savedStateHandle["share_deferred"] = false
        savedStateHandle["pending_share"] = Intent()
            .putParcelableArrayListExtra(TahanyService.EXTRA_URIS, ArrayList(uris))
            .putExtra(TahanyService.EXTRA_TEXT, text)
    }

    @Suppress("DEPRECATION")
    fun finishShare(approved: Boolean) {
        val share = pendingShare.value ?: return
        savedStateHandle["pending_share"] = null
        if (!approved || controller.value?.isConnected() != true) return
        val uris = share.getParcelableArrayListExtra<Uri>(TahanyService.EXTRA_URIS).orEmpty()
        if (uris.isNotEmpty()) sendUris(uris)
        else controller.value?.sendClipboard(share.getStringExtra(TahanyService.EXTRA_TEXT).orEmpty())
    }

    fun sendUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        val intent = Intent(app, TahanyService::class.java)
            .setAction(TahanyService.ACTION_SEND_URIS)
            .putParcelableArrayListExtra(TahanyService.EXTRA_URIS, ArrayList(uris))
        try {
            ContextCompat.startForegroundService(app, intent)
        } catch (_: Exception) {
            // The service is already foreground in normal use; a failure here
            // is surfaced by the next link-state update.
        }
    }

    fun refreshPermissions() {
        val app = getApplication<Application>()
        val intent = Intent(app, TahanyService::class.java)
            .setAction(TahanyService.ACTION_REFRESH)
        try {
            ContextCompat.startForegroundService(app, intent)
        } catch (_: Exception) {
            // Ignore; the watcher will also react to the next MediaStore change.
        }
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unbindService(connection) }
        super.onCleared()
    }
}
