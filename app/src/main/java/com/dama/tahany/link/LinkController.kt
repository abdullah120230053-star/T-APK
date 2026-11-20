package com.dama.tahany.link

import android.content.Context
import com.dama.tahany.data.SettingsRepository
import com.dama.tahany.model.ActiveCallState
import com.dama.tahany.model.DeviceRole
import com.dama.tahany.model.FoundPeer
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
import com.dama.tahany.util.toHex
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import kotlinx.coroutines.CoroutineScope
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
import java.io.File
import java.security.MessageDigest
import java.util.UUID

sealed interface LinkEvent {
    data class ClipboardReceived(val text: String, val timestamp: Long) : LinkEvent
    data class FileReceived(
        val file: File,
        val offer: TahanyMessage.FileOffer?,
        val size: Long,
    ) : LinkEvent

    data class ScreenshotReceived(
        val file: File,
        val name: String,
        val sha256: String,
        val size: Long,
        val takenAt: Long,
    ) : LinkEvent

    data class NotificationReceived(val event: TahanyMessage.NotificationEvent) : LinkEvent
    data class NotificationReplyReceived(val key: String, val text: String) : LinkEvent
    data class CallEventReceived(val event: TahanyMessage.CallEvent) : LinkEvent
    data class CallCommandReceived(val command: TahanyMessage.CallCommand) : LinkEvent
    data class ScreenCastOfferReceived(val offer: TahanyMessage.ScreenCastOffer) : LinkEvent
    data class ScreenCastAnswerReceived(val answer: TahanyMessage.ScreenCastAnswer) : LinkEvent
    data class ScreenCastStopReceived(val stop: TahanyMessage.ScreenCastStop) : LinkEvent
    data class TouchEventReceived(val touch: TahanyMessage.TouchEvent) : LinkEvent

    data class Notice(val message: String) : LinkEvent
    data class Problem(val message: String) : LinkEvent
}

/**
 * Owns one session: identity, Nearby transport, hello handshake, six-digit
 * confirmation, and routing of control messages to the service.
 *
 * Nothing is considered connected until both users (or a previously trusted
 * peer) have confirmed the code. A connection that is merely open can only
 * exchange hello messages.
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

    private val transport = NearbyTransport(context) { event ->
        scope.launch { handleTransport(event) }
    }

    private var identity: Identity? = null
    private var endpointId: String? = null
    private var localNonce: ByteArray? = null
    private var remoteNonce: ByteArray? = null
    private var remote: PeerInfo? = null
    private var sas: String? = null
    private var isHost = false
    private var confirmationSent = false
    private var remoteConfirmed = false
    private var handshakeTimeout: Job? = null
    private var pendingOffers = mutableMapOf<String, TahanyMessage.FileOffer>()
    private var pendingScreenshots = mutableMapOf<String, TahanyMessage.ScreenshotNotice>()
    private var lastClipboardHash: String? = null

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
        if (identity?.role == null) {
            _state.update { it.copy(phase = LinkPhase.ERROR, lastError = "Choose Phone or Tablet first.") }
            return
        }
        this.endpointId = endpointId
        this.remote = PeerInfo("", name, DeviceRole.TABLET)
        _state.update {
            it.copy(
                phase = LinkPhase.HANDSHAKE,
                peer = null,
                lastError = null,
                message = "Connecting to $name…",
            )
        }
        withMain { transport.connect(endpointId, name) }
        armHandshakeTimeout()
    }

    fun confirmPairing(accept: Boolean) {
        val local = identity ?: return
        val peer = remote ?: return
        val nonce1 = localNonce ?: return
        val nonce2 = remoteNonce ?: return
        val code = sas ?: return

        if (!accept) {
            send(TahanyMessage.PairRejected(reason = "The other device declined the code"))
            _state.update {
                it.copy(
                    phase = LinkPhase.ERROR,
                    awaitingConfirmation = false,
                    lastError = "Codes did not match; no data was exchanged.",
                )
            }
            disconnect()
            return
        }

        val proof = PairingCode.proof(local.deviceId, peer.deviceId, nonce1, nonce2, code)
        confirmationSent = true
        send(TahanyMessage.PairConfirm(proof = proof))
        _state.update {
            it.copy(
                awaitingConfirmation = false,
                message = "Code confirmed. Waiting for the other device…",
            )
        }
    }

    fun sendFileResult(transferId: String?, accepted: Boolean, savedName: String?, error: String?) {
        if (transferId == null) return
        send(
            TahanyMessage.FileResult(
                transferId = transferId,
                accepted = accepted,
                savedName = savedName,
                error = error,
            ),
        )
    }

    fun isConnected(): Boolean = _state.value.phase == LinkPhase.CONNECTED

    fun sendClipboard(text: String) {
        if (!isConnected()) {
            _state.update { it.copy(message = "Connect to the other device first.") }
            return
        }
        if (text.isBlank()) return
        if (text.toByteArray(Charsets.UTF_8).size > MAX_CLIPBOARD_BYTES) {
            _state.update { it.copy(lastError = "Clipboard text is too large to send.") }
            return
        }
        val now = System.currentTimeMillis()
        lastClipboardHash = sha256(text)
        send(TahanyMessage.ClipboardUpdate(text = text, timestamp = now))
        _state.update { it.copy(message = "Clipboard sent.") }
    }

    fun sendFile(file: File, mimeType: String) {
        if (!isConnected()) {
            _state.update { it.copy(message = "Connect to the other device first.") }
            return
        }
        scope.launch(Dispatchers.IO) {
            val name = FileNames.sanitize(file.name)
            val size = file.length()
            val sha = runCatching { Hashing.sha256(file) }.getOrNull()
            val transferId = UUID.randomUUID().toString()
            val offer = TahanyMessage.FileOffer(
                transferId = transferId,
                name = name,
                mimeType = mimeType,
                size = size,
                sha256 = sha,
            )
            send(offer)
            val payloadId = transport.sendFile(endpointId ?: return@launch, file, transferId)
            if (payloadId == null) {
                _state.update {
                    it.copy(
                        transfer = TransferState(
                            transferId,
                            name,
                            TransferDirection.OUTGOING,
                            status = TransferStatus.FAILED,
                            error = "Could not open the file",
                        ),
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    transfer = TransferState(
                        id = transferId,
                        name = name,
                        direction = TransferDirection.OUTGOING,
                        totalBytes = size,
                    ),
                    message = "Sending $name…",
                )
            }
            emit(LinkEvent.Notice("Sending $name"))
        }
    }

    fun sendScreenshot(
        file: File,
        name: String,
        sha256: String,
        size: Long,
        takenAt: Long,
    ) {
        if (!isConnected()) return
        val safeName = FileNames.sanitize(name)
        val transferId = UUID.randomUUID().toString()
        send(
            TahanyMessage.ScreenshotNotice(
                sha256 = sha256,
                name = safeName,
                size = size,
                takenAt = takenAt,
            ),
        )
        val payloadId = transport.sendFile(endpointId ?: return, file, transferId)
        if (payloadId == null) {
            emit(LinkEvent.Problem("Could not send screenshot $safeName"))
            return
        }
        _state.update {
            it.copy(transfer = TransferState(transferId, safeName, TransferDirection.OUTGOING, totalBytes = size))
        }
    }

    fun sendNotification(event: TahanyMessage.NotificationEvent) {
        if (!isConnected()) return
        send(event)
    }

    fun sendNotificationReply(key: String, text: String) {
        if (!isConnected()) return
        send(TahanyMessage.NotificationReply(key = key, text = text))
    }

    fun sendCallEvent(event: TahanyMessage.CallEvent) {
        if (!isConnected()) return
        send(event)
    }

    fun sendCallCommand(action: CallAction) {
        if (!isConnected()) return
        send(TahanyMessage.CallCommand(action = action))
    }

    fun sendScreenCastOffer(width: Int = 1280, height: Int = 720) {
        if (!isConnected()) return
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

    fun sendScreenCastStop(reason: String? = null) {
        if (!isConnected()) return
        _state.update {
            it.copy(screenCast = ScreenCastState(phase = ScreenCastPhase.IDLE))
        }
        send(TahanyMessage.ScreenCastStop(reason = reason))
    }

    fun sendTouchEvent(xRatio: Float, yRatio: Float, action: Int) {
        if (!isConnected()) return
        send(TahanyMessage.TouchEvent(xRatio = xRatio, yRatio = yRatio, action = action))
    }

    fun updateSpeakerState(isSpeakerOn: Boolean) {
        _state.update { current ->
            current.copy(activeCall = current.activeCall?.copy(isSpeakerOn = isSpeakerOn))
        }
    }

    fun disconnect() {
        handshakeTimeout?.cancel()
        endpointId?.let { transport.disconnect(it) }
        withMain { transport.stopSession() }
        resetSession(LinkPhase.IDLE)
    }

    fun close() {
        handshakeTimeout?.cancel()
        transport.stopSession()
    }

    private fun begin(asHost: Boolean) {
        scope.launch {
            val current = settings.current()
            val role = current.role
            if (role == null) {
                _state.update {
                    it.copy(phase = LinkPhase.ERROR, lastError = "Choose Phone or Tablet first.")
                }
                return@launch
            }
            identity = Identity(current.deviceId, current.deviceName, role)
            withMain { transport.stopSession() }
            resetSession(if (asHost) LinkPhase.ADVERTISING else LinkPhase.DISCOVERING)
            this@LinkController.isHost = asHost
            localNonce = PairingCode.newNonce()
            _state.update {
                it.copy(
                    phase = if (asHost) LinkPhase.ADVERTISING else LinkPhase.DISCOVERING,
                    message = if (asHost) "Visible as ${current.deviceName}" else "Looking for a Tahany device…",
                )
            }
            withMain {
                if (asHost) {
                    transport.advertise(advertisementName(current.deviceName))
                } else {
                    transport.discover()
                }
            }
        }
    }

    private suspend fun handleTransport(event: TransportEvent) {
        when (event) {
            is TransportEvent.PeerFound -> {
                _state.update { state ->
                    if (state.foundPeers.any { it.endpointId == event.endpointId }) {
                        state
                    } else {
                        state.copy(foundPeers = state.foundPeers + FoundPeer(event.endpointId, event.name))
                    }
                }
            }

            is TransportEvent.PeerLost -> _state.update { state ->
                state.copy(foundPeers = state.foundPeers.filterNot { it.endpointId == event.endpointId })
            }

            is TransportEvent.ConnectionInitiated -> {
                endpointId = event.endpointId
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

            is TransportEvent.BytesReceived -> handleBytes(event.bytes)
            is TransportEvent.FileReceived -> handleIncomingFile(event)
            is TransportEvent.TransferProgress -> handleTransferProgress(event)
            is TransportEvent.Disconnected -> {
                if (_state.value.phase != LinkPhase.IDLE) {
                    resetSession(LinkPhase.IDLE)
                    _state.update { it.copy(message = "The other device disconnected.") }
                }
            }

            is TransportEvent.Failure -> {
                _state.update {
                    it.copy(
                        phase = LinkPhase.ERROR,
                        lastError = event.message,
                    )
                }
                emit(LinkEvent.Problem(event.message))
            }

            TransportEvent.AdvertisingStarted -> _state.update {
                it.copy(phase = LinkPhase.ADVERTISING)
            }

            TransportEvent.Discovering -> _state.update {
                it.copy(phase = LinkPhase.DISCOVERING)
            }
        }
    }

    private suspend fun handleBytes(bytes: ByteArray) {
        val message = try {
            ProtocolCodec.decode(bytes)
        } catch (error: ProtocolException) {
            _state.update { it.copy(lastError = error.message) }
            return
        }
        when (message) {
            is TahanyMessage.Hello -> onHello(message)
            is TahanyMessage.HelloAck -> onHelloAck(message)
            is TahanyMessage.PairConfirm -> onPairConfirm(message)
            is TahanyMessage.PairAccepted -> {
                if (!confirmationSent) {
                    _state.update {
                        it.copy(
                            phase = LinkPhase.ERROR,
                            lastError = "The other device accepted before this device confirmed.",
                        )
                    }
                } else {
                    markConnected()
                }
            }
            is TahanyMessage.PairRejected -> {
                _state.update {
                    it.copy(
                        phase = LinkPhase.ERROR,
                        lastError = "The other device rejected the code.",
                        awaitingConfirmation = false,
                    )
                }
                disconnect()
            }

            is TahanyMessage.ClipboardUpdate -> {
                if (message.text.toByteArray(Charsets.UTF_8).size <= MAX_CLIPBOARD_BYTES) {
                    lastClipboardHash = sha256(message.text)
                    emit(LinkEvent.ClipboardReceived(message.text, message.timestamp))
                }
            }

            is TahanyMessage.FileOffer -> {
                pendingOffers[FileNames.sanitize(message.name)] = message
            }

            is TahanyMessage.ScreenshotNotice -> {
                pendingScreenshots[FileNames.sanitize(message.name)] = message
            }

            is TahanyMessage.FileResult -> {
                _state.update {
                    it.copy(
                        transfer = it.transfer?.copy(
                            status = if (message.accepted) TransferStatus.SUCCEEDED else TransferStatus.FAILED,
                            error = message.error,
                        ),
                    )
                }
            }

            is TahanyMessage.NotificationEvent -> {
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

            is TahanyMessage.NotificationReply -> emit(LinkEvent.NotificationReplyReceived(message.key, message.text))
            is TahanyMessage.NotificationAction -> Unit

            is TahanyMessage.CallEvent -> {
                _state.update { current ->
                    val callState = if (message.state == CallState.IDLE) null else ActiveCallState(
                        state = message.state,
                        number = message.number,
                        contactName = message.contactName,
                    )
                    current.copy(activeCall = callState)
                }
                emit(LinkEvent.CallEventReceived(message))
            }

            is TahanyMessage.CallCommand -> emit(LinkEvent.CallCommandReceived(message))

            is TahanyMessage.ScreenCastOffer -> {
                _state.update { current ->
                    current.copy(
                        screenCast = ScreenCastState(
                            phase = ScreenCastPhase.RECEIVING,
                            isReceiving = true,
                            width = message.width,
                            height = message.height,
                        ),
                    )
                }
                emit(LinkEvent.ScreenCastOfferReceived(message))
            }

            is TahanyMessage.ScreenCastAnswer -> {
                _state.update { current ->
                    current.copy(
                        screenCast = if (message.accepted) {
                            current.screenCast.copy(phase = ScreenCastPhase.STREAMING, isSharing = true)
                        } else {
                            current.screenCast.copy(phase = ScreenCastPhase.ERROR, error = message.reason)
                        },
                    )
                }
                emit(LinkEvent.ScreenCastAnswerReceived(message))
            }

            is TahanyMessage.ScreenCastStop -> {
                _state.update { current ->
                    current.copy(screenCast = ScreenCastState(phase = ScreenCastPhase.IDLE))
                }
                emit(LinkEvent.ScreenCastStopReceived(message))
            }

            is TahanyMessage.TouchEvent -> emit(LinkEvent.TouchEventReceived(message))
            is TahanyMessage.Ping -> send(TahanyMessage.Pong(timestamp = message.timestamp))
            is TahanyMessage.Pong -> Unit
            is TahanyMessage.ErrorMessage -> _state.update {
                it.copy(lastError = "${message.code}: ${message.message}")
            }
        }
    }

    private fun onHello(message: TahanyMessage.Hello) {
        val nonce = PairingCode.decodeNonce(message.nonce) ?: return
        remote = PeerInfo(message.deviceId, message.deviceName, message.role)
        remoteNonce = nonce
        if (!isHost) {
            sendHelloAck()
            deriveSas()
        }
    }

    private fun onHelloAck(message: TahanyMessage.HelloAck) {
        val nonce = PairingCode.decodeNonce(message.nonce) ?: return
        remote = PeerInfo(message.deviceId, message.deviceName, message.role)
        remoteNonce = nonce
        deriveSas()
    }

    private fun deriveSas() {
        val local = identity ?: return
        val peer = remote ?: return
        val nonce1 = localNonce ?: return
        val nonce2 = remoteNonce ?: return
        val code = PairingCode.derive(local.deviceId, peer.deviceId, nonce1, nonce2)
        sas = code
        handshakeTimeout?.cancel()
        _state.update {
            it.copy(
                phase = LinkPhase.HANDSHAKE,
                peer = peer,
                sas = code,
                awaitingConfirmation = true,
                message = "Compare this code on both screens.",
            )
        }
        scope.launch {
            if (settings.isTrusted(peer.deviceId)) {
                // A previously confirmed peer skips the manual comparison, but
                // the proof still binds the code to both nonces every session.
                confirmPairing(accept = true)
            }
        }
    }

    private fun onPairConfirm(message: TahanyMessage.PairConfirm) {
        val local = identity ?: return
        val peer = remote ?: return
        val nonce1 = localNonce ?: return
        val nonce2 = remoteNonce ?: return
        val code = sas ?: return
        val expected = PairingCode.proof(local.deviceId, peer.deviceId, nonce1, nonce2, code)
        if (!PairingCode.verifyProof(expected, message.proof)) {
            send(TahanyMessage.PairRejected(reason = "The confirmation proof did not match"))
            _state.update {
                it.copy(
                    phase = LinkPhase.ERROR,
                    lastError = "Pairing proof did not match. Disconnected.",
                    awaitingConfirmation = false,
                )
            }
            disconnect()
            return
        }
        remoteConfirmed = true
        scope.launch { settings.trustPeer(TrustedPeer(peer.deviceId, peer.name)) }
        if (confirmationSent) {
            send(TahanyMessage.PairAccepted())
            markConnected()
        } else {
            _state.update {
                it.copy(message = "The other device confirmed. Confirm the code here to finish.")
            }
        }
    }

    private fun markConnected() {
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
        emit(LinkEvent.Notice("Connected"))
    }

    private fun handleIncomingFile(event: TransportEvent.FileReceived) {
        val safeName = FileNames.sanitize(event.name)
        val screenshot = pendingScreenshots.remove(safeName)
        if (screenshot != null) {
            emit(
                LinkEvent.ScreenshotReceived(
                    file = event.file,
                    name = safeName,
                    sha256 = screenshot.sha256,
                    size = event.size,
                    takenAt = screenshot.takenAt,
                ),
            )
            return
        }
        val offer = pendingOffers.remove(safeName)
        emit(LinkEvent.FileReceived(event.file, offer, event.size))
    }

    private fun handleTransferProgress(event: TransportEvent.TransferProgress) {
        // Control messages are byte payloads too. Without the transfer id a
        // progress update belongs to one of them and must not mark a file done.
        val transferId = event.transferId ?: return
        if (_state.value.transfer?.id != transferId) return
        when (event.status) {
            PayloadTransferUpdate.Status.IN_PROGRESS -> _state.update { state ->
                state.copy(
                    transfer = state.transfer?.copy(
                        bytesTransferred = event.bytesTransferred,
                        totalBytes = event.totalBytes,
                    ),
                )
            }

            PayloadTransferUpdate.Status.SUCCESS -> _state.update { state ->
                state.copy(
                    transfer = state.transfer?.copy(
                        bytesTransferred = event.totalBytes,
                        totalBytes = event.totalBytes,
                        status = TransferStatus.SUCCEEDED,
                    ),
                )
            }

            PayloadTransferUpdate.Status.FAILURE,
            PayloadTransferUpdate.Status.CANCELED,
            -> _state.update { state ->
                state.copy(
                    transfer = state.transfer?.copy(
                        status = TransferStatus.FAILED,
                        error = "Transfer did not complete",
                    ),
                )
            }
        }
    }

    private fun sendHello() {
        val local = identity ?: return
        val nonce = localNonce ?: return
        send(
            TahanyMessage.Hello(
                deviceId = local.deviceId,
                deviceName = local.name,
                role = local.role,
                nonce = PairingCode.encodeNonce(nonce),
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

    private fun send(message: TahanyMessage) {
        val endpoint = endpointId ?: return
        val bytes = try {
            ProtocolCodec.encode(message)
        } catch (error: ProtocolException) {
            emit(LinkEvent.Problem(error.message ?: "Could not encode a message"))
            return
        }
        transport.sendBytes(endpoint, bytes)
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
                _state.update {
                    it.copy(
                        phase = LinkPhase.ERROR,
                        lastError = "No confirmed link after 45 seconds. Try again.",
                        awaitingConfirmation = false,
                    )
                }
            }
        }
    }

    private fun resetSession(phase: LinkPhase) {
        handshakeTimeout?.cancel()
        endpointId = null
        remote = null
        localNonce = null
        remoteNonce = null
        sas = null
        confirmationSent = false
        remoteConfirmed = false
        pendingOffers = mutableMapOf()
        pendingScreenshots = mutableMapOf()
        _state.value = LinkUiState(phase = phase)
    }

    private fun advertisementName(name: String): String =
        "Tahany | ${name.take(40)}".take(80)

    private fun emit(event: LinkEvent) {
        _events.tryEmit(event)
    }

    private fun withMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main) { block() }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .toHex()

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 45_000L
        const val MAX_CLIPBOARD_BYTES = 24 * 1024
    }
}
