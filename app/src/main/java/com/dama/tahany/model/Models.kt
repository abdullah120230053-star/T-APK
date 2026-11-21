package com.dama.tahany.model

import kotlinx.serialization.Serializable

@Serializable
enum class DeviceRole {
    PHONE,
    TABLET,
}

enum class LinkPhase {
    IDLE,
    ADVERTISING,
    DISCOVERING,
    HANDSHAKE,
    CONNECTED,
    ERROR,
}

data class TrustedPeer(
    val deviceId: String,
    val name: String,
)

data class PeerInfo(
    val deviceId: String,
    val name: String,
    val role: DeviceRole,
)

enum class TransferDirection {
    INCOMING,
    OUTGOING,
}

enum class TransferStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
}

data class TransferState(
    val id: String,
    val name: String,
    val direction: TransferDirection,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val status: TransferStatus = TransferStatus.RUNNING,
    val error: String? = null,
) {
    val progress: Float
        get() = if (totalBytes <= 0L) 0f else (bytesTransferred.toFloat() / totalBytes).coerceIn(0f, 1f)
}

data class ActiveCallState(
    val state: com.dama.tahany.protocol.CallState,
    val number: String? = null,
    val contactName: String? = null,
    val isSpeakerOn: Boolean = false,
)

data class MirroredNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val hasReply: Boolean,
)

enum class ScreenCastPhase {
    IDLE,
    STARTING,
    STREAMING,
    RECEIVING,
    ERROR,
}

data class ScreenCastState(
    val phase: ScreenCastPhase = ScreenCastPhase.IDLE,
    val isSharing: Boolean = false,
    val isReceiving: Boolean = false,
    val width: Int = 1280,
    val height: Int = 720,
    val error: String? = null,
)

data class LinkUiState(
    val phase: LinkPhase = LinkPhase.IDLE,
    val peer: PeerInfo? = null,
    val sas: String? = null,
    val awaitingConfirmation: Boolean = false,
    val trusted: Boolean = false,
    val hostAddress: String? = null,
    val transfer: TransferState? = null,
    val lastError: String? = null,
    val message: String? = null,
    val activeCall: ActiveCallState? = null,
    val mirroredNotifications: List<MirroredNotification> = emptyList(),
    val screenCast: ScreenCastState = ScreenCastState(),
)

data class AppSettings(
    val role: DeviceRole? = null,
    val deviceId: String = "",
    val deviceName: String = "",
    val autoScreenshot: Boolean = true,
    val setupComplete: Boolean = false,
    val trustedPeers: List<TrustedPeer> = emptyList(),
    val blockedPackages: Set<String> = emptySet(),
)
