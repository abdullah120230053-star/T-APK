package com.dama.tahany.link

import android.content.Context
import android.net.Uri
import java.io.InputStream
import com.dama.tahany.data.SettingsRepository
import com.dama.tahany.model.ActiveCallState
import com.dama.tahany.model.DeviceRole
import com.dama.tahany.model.LinkPhase
import com.dama.tahany.model.LinkUiState
import com.dama.tahany.model.MirroredNotification
import com.dama.tahany.model.PeerInfo
import com.dama.tahany.model.ScreenCastPhase
import com.dama.tahany.model.ScreenCastState
import com.dama.tahany.model.TransferDirection
import com.dama.tahany.model.TransferState
import com.dama.tahany.model.TransferStatus
import com.dama.tahany.model.TrustedPeer
import com.dama.tahany.protocol.CallAction
import com.dama.tahany.protocol.CallState
import com.dama.tahany.protocol.PROTOCOL_VERSION
import com.dama.tahany.protocol.PairingCode
import com.dama.tahany.protocol.ProtocolCodec
import com.dama.tahany.protocol.ProtocolException
import com.dama.tahany.protocol.TahanyMessage
import com.dama.tahany.util.FileNames
import com.dama.tahany.util.Hashing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

sealed interface LinkEvent {
    data class ClipboardReceived(val text: String, val timestamp: Long) : LinkEvent
    data class FileReceived(
        val uri: Uri,
        val offer: TahanyMessage.FileOffer,
        val session: Int,
    ) : LinkEvent

    data class ScreenshotReceived(
        val uri: Uri,
        val notice: TahanyMessage.ScreenshotNotice,
        val session: Int,
    ) : LinkEvent

    data class NotificationReceived(val event: TahanyMessage.NotificationEvent) : LinkEvent
    data class NotificationReplyReceived(val key: String, val text: String) : LinkEvent
    data class CallEventReceived(val event: TahanyMessage.CallEvent) : LinkEvent
    data class CallCommandReceived(val command: TahanyMessage.CallCommand) : LinkEvent
    data class ScreenCastOfferReceived(val offer: TahanyMessage.ScreenCastOffer) : LinkEvent
    data class ScreenCastAnswerReceived(val answer: TahanyMessage.ScreenCastAnswer) : LinkEvent
    data object ScreenCastStopReceived : LinkEvent
    data class ScreenStreamReceived(val stream: InputStream) : LinkEvent
    data class TouchEventReceived(val touch: TahanyMessage.TouchEvent) : LinkEvent

}

/**
 * Owns one session: identity, Nearby transport, hello handshake, six-digit
 * confirmation, and routing of control messages to the service.
 *
 * Both users confirm the code before any feature messages are accepted.
 */
class LinkController(
    context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private data class Identity(
        val deviceId: String,
        val name: String,
        val role: DeviceRole,
    )

    private val _state = MutableStateFlow(LinkUiState())
    val state: StateFlow<LinkUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<LinkEvent> = _events.asSharedFlow()

    private val transport = WifiTransport(context, scope) { event ->
        scope.launch { handleTransport(event) }
    }

    private var identity: Identity? = null
    private var endpointId: String? = null
    private var localNonce: ByteArray? = null
    private var remoteNonce: ByteArray? = null
    private var remoteCommitment: String? = null
    private var remote: PeerInfo? = null
    private var sas: String? = null
    private var isHost = false
    private var confirmationSent = false
    private var remoteConfirmed = false
    private var handshakeTimeout: Job? = null
    private var channelToken: ByteArray? = null
    private var session = 0
    private val transferLock = Mutex()
    private var activeTransfer: Pair<String, CompletableDeferred<Boolean>>? = null
    private val pendingMetadata = mutableMapOf<Long, TahanyMessage>()
    private val completedFiles = mutableMapOf<Long, Uri>()

    init {
        scope.launch {
            settings.ensureInitialized()
            val current = settings.current()
            val role = current.role
            if (role != null) {
                identity = Identity(current.deviceId, current.deviceName, role)
            }
            _state.update { it.copy(message = null) }
        }
    }

    fun host() = begin(asHost = true)

    fun join() = begin(asHost = false)

    fun connect(endpointId: String, name: String) {
        if (state.value.phase != LinkPhase.DISCOVERING) return
        if (identity?.role == null) {
            _state.update { it.copy(phase = LinkPhase.ERROR, lastError = "Choose Phone or Tablet first.") }
            return
        }
        this.endpointId = endpointId
        _state.update {
            it.copy(
                phase = LinkPhase.HANDSHAKE,
                peer = null,
                lastError = null,
                message = "Connecting to $name…",
            )
        }
        withMain { transport.connect(endpointId) }
        armHandshakeTimeout()
    }

    fun confirmPairing(accept: Boolean) {
        if (_state.value.phase != LinkPhase.HANDSHAKE || confirmationSent) return
        val proof = pairingProof() ?: return
        if (!accept) {
            send(TahanyMessage.PairRejected())
            endSession("Pairing declined.")
            return
        }
        confirmationSent = true
        send(TahanyMessage.PairConfirm(proof = proof))
        _state.update {
            it.copy(awaitingConfirmation = false, message = "Waiting for the other device to confirm…")
        }
        completePairing()
    }

    fun isConnected(): Boolean = state.value.phase == LinkPhase.CONNECTED

    fun sendFileResult(transferId: String, sourceSession: Int, savedName: String?, error: String?) {
        if (sourceSession != session || !isConnected()) return
        send(TahanyMessage.FileResult(transferId = transferId, accepted = error == null,
            savedName = savedName, error = error))
    }

    fun sendClipboard(text: String) {
        if (!isConnected()) {
            showMessage("Connect to the other device first.")
            return
        }
        if (text.isBlank()) {
            showMessage("Your clipboard has no text to send.")
            return
        }
        if (send(TahanyMessage.ClipboardUpdate(text = text, timestamp = System.currentTimeMillis()))) {
            showMessage("Clipboard sent.")
        }
    }

    fun showMessage(message: String) {
        _state.update { it.copy(message = message) }
    }

    suspend fun sendFile(
        file: File,
        mimeType: String,
        screenshotAt: Long? = null,
        precomputedHash: String? = null,
    ): Boolean {
        val sourceSession = session
        try {
            return transferLock.withLock {
                val endpoint = endpointId
                if (!isConnected() || endpoint == null || sourceSession != session) return@withLock false
                val name = FileNames.sanitize(file.name)
                val size = file.length()
                val sha = precomputedHash ?: withContext(Dispatchers.IO) {
                    runCatching { Hashing.sha256(file) }.getOrNull()
                }
                if (!isConnected() || sourceSession != session) return@withLock false
                if (sha == null) {
                    showMessage("Could not read $name.")
                    return@withLock false
                }
                val payload = runCatching { transport.createFilePayload(file) }.getOrElse {
                    showMessage("Could not open $name.")
                    return@withLock false
                }
                val id = UUID.randomUUID().toString()
                val metadata = if (screenshotAt == null) {
                    TahanyMessage.FileOffer(transferId = id, name = name, mimeType = mimeType,
                        size = size, sha256 = sha, payloadId = payload.id)
                } else {
                    TahanyMessage.ScreenshotNotice(sha256 = sha, name = name, size = size,
                        takenAt = screenshotAt, payloadId = payload.id, transferId = id)
                }
                if (!send(metadata)) return@withLock false
                _state.update {
                    it.copy(transfer = TransferState(id, name, TransferDirection.OUTGOING, totalBytes = size),
                        message = "Sending $name…")
                }
                val result = CompletableDeferred<Boolean>()
                activeTransfer = id to result
                try {
                    transport.sendFile(endpoint, payload, id)
                    val accepted = withTimeoutOrNull(TRANSFER_TIMEOUT_MS) { result.await() }
                    if (accepted == null) {
                        transport.cancelPayload(payload.id)
                        finishOutgoing(id, false, "The other device did not confirm delivery.")
                    }
                    accepted == true
                } finally {
                    activeTransfer = null
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                file.delete()
                file.parentFile?.delete()
            }
        }
    }

    fun sendNotification(event: TahanyMessage.NotificationEvent) {
        if (!isConnected() || identity?.role != DeviceRole.PHONE) return
        send(event)
    }

    fun sendNotificationReply(key: String, text: String) {
        if (!isConnected() || identity?.role != DeviceRole.TABLET || text.isBlank()) return
        send(TahanyMessage.NotificationReply(key = key, text = text.take(2_000)))
    }

    fun sendCallEvent(event: TahanyMessage.CallEvent) {
        if (!isConnected() || identity?.role != DeviceRole.PHONE) return
        send(event)
    }

    fun sendCallCommand(action: CallAction) {
        if (!isConnected() || identity?.role != DeviceRole.TABLET || state.value.activeCall == null) return
        send(TahanyMessage.CallCommand(action = action))
    }

    fun sendCallResult(action: CallAction, succeeded: Boolean) {
        if (isConnected()) send(TahanyMessage.CallResult(action = action, succeeded = succeeded))
    }

    fun sendReplyFailure() {
        if (isConnected()) send(TahanyMessage.ErrorMessage(code = "reply_failed",
            message = "This notification can no longer accept a reply."))
    }

    fun sendScreenCastOffer(width: Int = 1280, height: Int = 720) {
        if (!isConnected() || identity?.role != DeviceRole.PHONE || remote?.role != DeviceRole.TABLET) return
        _state.update {
            it.copy(
                screenCast = ScreenCastState(
                    phase = ScreenCastPhase.STARTING,
                    isSharing = true,
                    width = width,
                    height = height,
                ),
            )
        }
        send(TahanyMessage.ScreenCastOffer(width = width, height = height))
    }

    fun sendScreenCastAnswer(accepted: Boolean, reason: String? = null) {
        if (!isConnected()) return
        send(TahanyMessage.ScreenCastAnswer(accepted = accepted, reason = reason))
    }

    fun startScreenStream(stream: InputStream, onFailure: () -> Unit) {
        val endpoint = endpointId
        if (endpoint == null || !isConnected()) {
            runCatching { stream.close() }
            onFailure()
            return
        }
        transport.sendStream(endpoint, stream, onFailure)
    }

    fun markScreenReceiving() {
        _state.update {
            it.copy(screenCast = it.screenCast.copy(phase = ScreenCastPhase.RECEIVING, isReceiving = true))
        }
    }

    fun sendScreenCastStop(reason: String? = null) {
        if (isConnected()) send(TahanyMessage.ScreenCastStop(reason = reason))
        resetScreenCast(reason)
    }

    private fun resetScreenCast(reason: String?) {
        val state = if (reason == null) ScreenCastState()
        else ScreenCastState(phase = ScreenCastPhase.ERROR, error = reason)
        _state.update { it.copy(screenCast = state) }
    }

    fun sendTouchEvent(x: Float, y: Float, endX: Float, endY: Float, action: Int) {
        if (!isConnected() || identity?.role != DeviceRole.TABLET || !state.value.screenCast.isReceiving) return
        send(TahanyMessage.TouchEvent(xRatio = x, yRatio = y, action = action,
            endXRatio = endX, endYRatio = endY))
    }

    fun updateSpeakerState(isSpeakerOn: Boolean) {
        _state.update { current ->
            current.copy(activeCall = current.activeCall?.copy(isSpeakerOn = isSpeakerOn))
        }
    }

    fun disconnect() = endSession()

    private fun endSession(error: String? = null) {
        transport.stopSession()
        resetSession(if (error == null) LinkPhase.IDLE else LinkPhase.ERROR)
        if (error != null) _state.update { it.copy(lastError = error) }
    }

    fun close() = endSession()

    private fun begin(asHost: Boolean) {
        scope.launch {
            if (state.value.phase !in setOf(LinkPhase.IDLE, LinkPhase.ERROR)) return@launch
            settings.ensureInitialized()
            val current = settings.current()
            if (state.value.phase !in setOf(LinkPhase.IDLE, LinkPhase.ERROR)) return@launch
            val role = current.role
            if (role == null) {
                _state.update {
                    it.copy(phase = LinkPhase.ERROR, lastError = "Choose Phone or Tablet first.")
                }
                return@launch
            }
            identity = Identity(current.deviceId, current.deviceName, role)
            resetSession(if (asHost) LinkPhase.ADVERTISING else LinkPhase.DISCOVERING)
            this@LinkController.isHost = asHost
            localNonce = PairingCode.newNonce()
            _state.update {
                it.copy(
                    phase = if (asHost) LinkPhase.ADVERTISING else LinkPhase.DISCOVERING,
                    message = if (asHost) "Starting a Wi-Fi host…" else null,
                )
            }
            if (asHost) withMain { transport.advertise() }
        }
    }

    private suspend fun handleTransport(event: TransportEvent) {
        when (event) {
            is TransportEvent.Hosting -> _state.update {
                it.copy(hostAddress = event.address, message = "Enter this address on the joining device.")
            }

            is TransportEvent.ConnectionInitiated -> {
                endpointId = event.endpointId
                channelToken = event.channelToken
                _state.update {
                    it.copy(
                        phase = LinkPhase.HANDSHAKE,
                        message = "Connecting to ${event.name}…",
                    )
                }
            }

            is TransportEvent.Connected -> {
                endpointId = event.endpointId
                _state.update {
                    it.copy(
                        phase = LinkPhase.HANDSHAKE,
                        message = "Link open. Checking the code…",
                    )
                }
                // Host speaks first. The joiner replies with HelloAck, which
                // keeps the handshake one-directional and avoids a hello loop.
                if (isHost) sendHello()
                armHandshakeTimeout()
            }

            is TransportEvent.BytesReceived -> if (event.endpointId == endpointId) handleBytes(event.bytes)
            is TransportEvent.FileReceived -> handleIncomingFile(event)
            is TransportEvent.StreamReceived -> {
                if (event.endpointId == endpointId && isConnected() && identity?.role == DeviceRole.TABLET &&
                    state.value.screenCast.phase == ScreenCastPhase.STARTING
                ) {
                    if (!_events.tryEmit(LinkEvent.ScreenStreamReceived(event.stream))) event.stream.close()
                } else {
                    runCatching { event.stream.close() }
                }
            }
            is TransportEvent.TransferProgress -> if (event.endpointId == endpointId) handleTransferProgress(event)
            is TransportEvent.Disconnected -> {
                if (event.endpointId == endpointId) {
                    endSession()
                    showMessage("The other device disconnected.")
                }
            }

            is TransportEvent.Failure -> endSession(event.message)
        }
    }

    private suspend fun handleBytes(bytes: ByteArray) {
        val message = try {
            ProtocolCodec.decode(bytes)
        } catch (_: ProtocolException) {
            if (state.value.phase == LinkPhase.HANDSHAKE) {
                endSession("Pairing failed. Install the Wi-Fi version of Tahany on both devices.")
            }
            return
        }
        if (!isConnected()) {
            if (state.value.phase != LinkPhase.HANDSHAKE) return
            when (message) {
                is TahanyMessage.Hello -> if (!isHost && remoteCommitment == null) onHello(message)
                is TahanyMessage.HelloAck -> if (isHost && sas == null) onHelloAck(message)
                is TahanyMessage.PairReveal -> if (!isHost && sas == null) onPairReveal(message)
                is TahanyMessage.PairConfirm -> onPairConfirm(message)
                is TahanyMessage.PairRejected -> endSession("The other device declined pairing.")
                else -> Unit
            }
            return
        }
        when (message) {
            is TahanyMessage.Hello, is TahanyMessage.HelloAck, is TahanyMessage.PairConfirm,
            is TahanyMessage.PairRejected, is TahanyMessage.PairReveal -> Unit
            is TahanyMessage.ClipboardUpdate -> emit(LinkEvent.ClipboardReceived(message.text, message.timestamp))
            is TahanyMessage.FileOffer -> acceptMetadata(message.payloadId, message)
            is TahanyMessage.ScreenshotNotice -> acceptMetadata(message.payloadId, message)
            is TahanyMessage.FileResult -> finishOutgoing(message.transferId, message.accepted, message.error)

            is TahanyMessage.NotificationEvent -> {
                if (identity?.role != DeviceRole.TABLET || remote?.role != DeviceRole.PHONE) return
                _state.update { current ->
                    val item = MirroredNotification(
                        key = message.key,
                        packageName = message.packageName,
                        appLabel = message.appLabel,
                        title = message.title,
                        text = message.text,
                        postedAt = message.postedAt,
                        hasReply = message.hasReply,
                    )
                    current.copy(
                        mirroredNotifications = (listOf(item) + current.mirroredNotifications.filterNot { it.key == item.key }).take(50),
                    )
                }
                emit(LinkEvent.NotificationReceived(message))
            }

            is TahanyMessage.NotificationReply -> if (identity?.role == DeviceRole.PHONE && remote?.role == DeviceRole.TABLET) {
                emit(LinkEvent.NotificationReplyReceived(message.key, message.text.take(2_000)))
            }
            is TahanyMessage.NotificationAction -> Unit

            is TahanyMessage.CallEvent -> {
                if (identity?.role != DeviceRole.TABLET || remote?.role != DeviceRole.PHONE) return
                _state.update { current ->
                    val callState = if (message.state == CallState.IDLE) null else ActiveCallState(
                        state = message.state,
                        number = message.number,
                        contactName = message.contactName,
                        isSpeakerOn = current.activeCall?.isSpeakerOn ?: false,
                    )
                    current.copy(activeCall = callState)
                }
                emit(LinkEvent.CallEventReceived(message))
            }

            is TahanyMessage.CallCommand -> if (identity?.role == DeviceRole.PHONE && remote?.role == DeviceRole.TABLET) {
                emit(LinkEvent.CallCommandReceived(message))
            }

            is TahanyMessage.CallResult -> {
                if (identity?.role != DeviceRole.TABLET || remote?.role != DeviceRole.PHONE) return
                if (!message.succeeded) {
                    showMessage("The phone could not complete the call action. Check its permissions.")
                } else if (message.action == CallAction.SPEAKER_ON || message.action == CallAction.SPEAKER_OFF) {
                    updateSpeakerState(message.action == CallAction.SPEAKER_ON)
                }
            }
            is TahanyMessage.ScreenCastOffer -> {
                if (identity?.role != DeviceRole.TABLET || remote?.role != DeviceRole.PHONE ||
                    state.value.screenCast.phase !in setOf(ScreenCastPhase.IDLE, ScreenCastPhase.ERROR)
                ) return
                if (message.width !in 16..1280 || message.height !in 16..1280) {
                    sendScreenCastAnswer(false, "Unsupported screen size.")
                    return
                }
                _state.update { current ->
                    current.copy(
                        screenCast = ScreenCastState(
                            phase = ScreenCastPhase.STARTING,
                            width = message.width,
                            height = message.height,
                        ),
                    )
                }
                emit(LinkEvent.ScreenCastOfferReceived(message))
            }

            is TahanyMessage.ScreenCastAnswer -> {
                if (identity?.role != DeviceRole.PHONE || remote?.role != DeviceRole.TABLET ||
                    state.value.screenCast.phase != ScreenCastPhase.STARTING
                ) return
                _state.update { current ->
                    current.copy(
                        screenCast = if (message.accepted) {
                            current.screenCast.copy(phase = ScreenCastPhase.STREAMING, isSharing = true)
                        } else {
                            ScreenCastState(phase = ScreenCastPhase.ERROR, error = message.reason)
                        },
                    )
                }
                emit(LinkEvent.ScreenCastAnswerReceived(message))
            }

            is TahanyMessage.ScreenCastStop -> {
                resetScreenCast(message.reason)
                emit(LinkEvent.ScreenCastStopReceived)
            }

            is TahanyMessage.TouchEvent -> if (identity?.role == DeviceRole.PHONE &&
                remote?.role == DeviceRole.TABLET && state.value.screenCast.phase == ScreenCastPhase.STREAMING
            ) {
                emit(LinkEvent.TouchEventReceived(message))
            }
            is TahanyMessage.Ping -> send(TahanyMessage.Pong(timestamp = message.timestamp))
            is TahanyMessage.Pong -> Unit
            is TahanyMessage.ErrorMessage -> showMessage(message.message.take(160))
        }
    }

    // Commit before receiving the joiner's nonce. Neither side can choose
    // its nonce after seeing the other nonce and grind for a matching code.
    private fun onHello(message: TahanyMessage.Hello) {
        if (message.nonce.length != 64) {
            endSession("Invalid Wi-Fi pairing commitment.")
            return
        }
        remoteCommitment = message.nonce
        remote = PeerInfo(message.deviceId, message.deviceName, message.role)
        sendHelloAck()
    }

    private fun onHelloAck(message: TahanyMessage.HelloAck) {
        val nonce = PairingCode.decodeNonce(message.nonce) ?: return
        val local = localNonce ?: return
        remote = PeerInfo(message.deviceId, message.deviceName, message.role)
        remoteNonce = nonce
        send(TahanyMessage.PairReveal(nonce = PairingCode.encodeNonce(local)))
        deriveSas()
    }

    private fun onPairReveal(message: TahanyMessage.PairReveal) {
        val nonce = PairingCode.decodeNonce(message.nonce) ?: return
        val commitment = remoteCommitment ?: return
        if (!PairingCode.matchesCommitment(nonce, commitment)) {
            endSession("The Wi-Fi pairing code could not be verified.")
            return
        }
        remoteNonce = nonce
        deriveSas()
    }

    private fun deriveSas() {
        val local = identity ?: return
        val peer = remote ?: return
        val nonce1 = localNonce ?: return
        val nonce2 = remoteNonce ?: return
        val token = channelToken ?: return
        if (local.deviceId == peer.deviceId || local.role == peer.role) {
            endSession("Choose Phone on one device and Tablet on the other.")
            return
        }
        sas = PairingCode.derive(local.deviceId, peer.deviceId, nonce1, nonce2, token)
        armHandshakeTimeout()
        _state.update {
            it.copy(phase = LinkPhase.HANDSHAKE, peer = peer, sas = sas,
                awaitingConfirmation = true, message = "Compare the code on both devices.")
        }
    }

    private fun pairingProof(): String? {
        val local = identity ?: return null
        val peer = remote ?: return null
        val first = localNonce ?: return null
        val second = remoteNonce ?: return null
        val code = sas ?: return null
        return PairingCode.proof(local.deviceId, peer.deviceId, first, second, code)
    }

    private fun onPairConfirm(message: TahanyMessage.PairConfirm) {
        val expected = pairingProof() ?: return
        if (!PairingCode.verifyProof(expected, message.proof)) {
            send(TahanyMessage.PairRejected())
            endSession("Pairing proof did not match.")
            return
        }
        remoteConfirmed = true
        completePairing()
    }

    private fun completePairing() {
        if (!confirmationSent || !remoteConfirmed || isConnected()) return
        remote?.let { peer -> scope.launch { settings.trustPeer(TrustedPeer(peer.deviceId, peer.name)) } }
        markConnected()
    }

    private fun markConnected() {
        transport.authorize(endpointId ?: return)
        handshakeTimeout?.cancel()
        _state.update {
            it.copy(
                phase = LinkPhase.CONNECTED,
                awaitingConfirmation = false,
                trusted = true,
                sas = null,
                message = "Connected to ${remote?.name ?: "the other device"}.",
            )
        }
    }

    private fun acceptMetadata(payloadId: Long, message: TahanyMessage) {
        val uri = completedFiles.remove(payloadId)
        if (uri != null) {
            deliverFile(uri, message)
        } else if (pendingMetadata.size < MAX_PENDING_FILES) {
            pendingMetadata.putIfAbsent(payloadId, message)
        }
    }

    private fun handleIncomingFile(event: TransportEvent.FileReceived) {
        if (event.endpointId != endpointId || !isConnected()) {
            transport.deleteIncomingFile(event.uri)
            return
        }
        val metadata = pendingMetadata.remove(event.payloadId)
        if (metadata != null) {
            deliverFile(event.uri, metadata)
        } else if (completedFiles.size < MAX_PENDING_FILES) {
            completedFiles[event.payloadId] = event.uri
            scope.launch {
                delay(10_000)
                if (completedFiles[event.payloadId] == event.uri) {
                    completedFiles.remove(event.payloadId)
                    transport.deleteIncomingFile(event.uri)
                }
            }
        } else {
            transport.deleteIncomingFile(event.uri)
        }
    }

    private fun deliverFile(uri: Uri, message: TahanyMessage) {
        val event = when (message) {
            is TahanyMessage.FileOffer -> LinkEvent.FileReceived(uri, message, session)
            is TahanyMessage.ScreenshotNotice -> LinkEvent.ScreenshotReceived(uri, message, session)
            else -> error("Unexpected file metadata")
        }
        if (!_events.tryEmit(event)) transport.deleteIncomingFile(uri)
    }

    fun deleteIncomingFile(uri: Uri) = transport.deleteIncomingFile(uri)

    private fun handleTransferProgress(event: TransportEvent.TransferProgress) {
        val transferId = event.transferId
        if (activeTransfer?.first != transferId || state.value.transfer?.id != transferId) return
        when (event.status) {
            TransferProgressStatus.IN_PROGRESS -> _state.update { current ->
                current.copy(transfer = current.transfer?.copy(bytesTransferred = event.bytesTransferred))
            }
            TransferProgressStatus.FAILURE ->
                finishOutgoing(transferId, false, "Transfer did not complete.")
        }
    }

    fun recordIncoming(sourceSession: Int, id: String, name: String, size: Long, error: String?) {
        if (sourceSession != session || !isConnected() || activeTransfer != null) return
        _state.update {
            it.copy(transfer = TransferState(
                id = id,
                name = FileNames.sanitize(name),
                direction = TransferDirection.INCOMING,
                bytesTransferred = if (error == null) size else 0L,
                totalBytes = size,
                status = if (error == null) TransferStatus.SUCCEEDED else TransferStatus.FAILED,
                error = error,
            ))
        }
    }

    private fun finishOutgoing(id: String, accepted: Boolean, error: String?) {
        val transfer = activeTransfer ?: return
        if (transfer.first != id || transfer.second.isCompleted) return
        _state.update { current ->
            current.copy(
                transfer = current.transfer?.let { state -> state.copy(
                    bytesTransferred = if (accepted) state.totalBytes else state.bytesTransferred,
                    status = if (accepted) TransferStatus.SUCCEEDED else TransferStatus.FAILED,
                    error = if (accepted) null else error ?: "The other device could not save this file.",
                ) },
                message = if (accepted) "File delivered." else "Transfer failed.",
            )
        }
        transfer.second.complete(accepted)
    }

    private fun sendHello() {
        val local = identity ?: return
        val nonce = localNonce ?: return
        send(
            TahanyMessage.Hello(
                deviceId = local.deviceId,
                deviceName = local.name,
                role = local.role,
                nonce = PairingCode.commitNonce(nonce),
                appVersion = PROTOCOL_VERSION.toString(),
            ),
        )
    }

    private fun sendHelloAck() {
        val local = identity ?: return
        val nonce = localNonce ?: return
        send(
            TahanyMessage.HelloAck(
                deviceId = local.deviceId,
                deviceName = local.name,
                role = local.role,
                nonce = PairingCode.encodeNonce(nonce),
                appVersion = PROTOCOL_VERSION.toString(),
            ),
        )
    }

    private fun send(message: TahanyMessage): Boolean {
        val endpoint = endpointId ?: return false
        val bytes = try {
            ProtocolCodec.encode(message)
        } catch (error: ProtocolException) {
            showMessage(error.message ?: "Could not encode a message.")
            return false
        }
        transport.sendBytes(endpoint, bytes)
        return true
    }

    private fun armHandshakeTimeout() {
        handshakeTimeout?.cancel()
        handshakeTimeout = scope.launch {
            delay(HANDSHAKE_TIMEOUT_MS)
            if (_state.value.phase in setOf(
                    LinkPhase.ADVERTISING,
                    LinkPhase.DISCOVERING,
                    LinkPhase.HANDSHAKE,
                )
            ) {
                endSession("Pairing timed out. Try again.")
            }
        }
    }

    private fun resetSession(phase: LinkPhase) {
        handshakeTimeout?.cancel()
        endpointId = null
        remote = null
        localNonce = null
        remoteNonce = null
        remoteCommitment = null
        sas = null
        confirmationSent = false
        remoteConfirmed = false
        channelToken = null
        session += 1
        activeTransfer?.second?.complete(false)
        pendingMetadata.clear()
        completedFiles.values.forEach(transport::deleteIncomingFile)
        completedFiles.clear()
        _state.value = LinkUiState(phase = phase)
    }

    private fun emit(event: LinkEvent) {
        _events.tryEmit(event)
    }

    private fun withMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main) { block() }
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 45_000L
        private const val TRANSFER_TIMEOUT_MS = 15 * 60_000L
        private const val MAX_PENDING_FILES = 32
    }
}
