package com.dama.tahany.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.core.content.ContextCompat
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
class TahanyViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepository = SettingsRepository(application)
    private val controller = MutableStateFlow<com.dama.tahany.link.LinkController?>(null)

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
            controller.value = binder.controller()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            controller.value = null
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
        viewModelScope.launch { settingsRepository.setRole(role) }
    }

    fun setAutoScreenshot(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoScreenshot(enabled) }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setWifiOnly(enabled) }
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
        controller.value?.sendCallCommand(com.dama.tahany.protocol.CallAction.ANSWER)
    }

    fun declineCall() {
        controller.value?.sendCallCommand(com.dama.tahany.protocol.CallAction.DECLINE)
    }

    fun toggleSpeaker() {
        val currentSpeaker = linkState.value.activeCall?.isSpeakerOn ?: false
        val nextAction = if (currentSpeaker) com.dama.tahany.protocol.CallAction.SPEAKER_OFF else com.dama.tahany.protocol.CallAction.SPEAKER_ON
        controller.value?.sendCallCommand(nextAction)
        controller.value?.updateSpeakerState(!currentSpeaker)
    }

    fun replyNotification(key: String, text: String) {
        controller.value?.sendNotificationReply(key, text)
    }

    fun startScreenCast(width: Int = 1280, height: Int = 720) {
        controller.value?.sendScreenCastOffer(width, height)
    }

    fun stopScreenCast() {
        controller.value?.sendScreenCastStop()
    }

    fun sendClipboard() {
        controller.value?.sendClipboard(readClipboard() ?: "")
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

    private fun readClipboard(): String? {
        val app = getApplication<Application>()
        val manager = app.getSystemService(android.content.ClipboardManager::class.java) ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(app)?.toString()?.takeIf { it.isNotBlank() }
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unbindService(connection) }
        super.onCleared()
    }
}
