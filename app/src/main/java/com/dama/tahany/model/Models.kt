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

data class FoundPeer(
    val endpointId: String,
    val name: String,
)

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

data class LinkUiState(
    val phase: LinkPhase = LinkPhase.IDLE,
    val peer: PeerInfo? = null,
    val sas: String? = null,
    val awaitingConfirmation: Boolean = false,
    val trusted: Boolean = false,
    val foundPeers: List<FoundPeer> = emptyList(),
    val transfer: TransferState? = null,
    val lastError: String? = null,
    val message: String? = null,
)

data class AppSettings(
    val role: DeviceRole? = null,
    val deviceId: String = "",
    val deviceName: String = "",
    val autoScreenshot: Boolean = true,
    val wifiOnly: Boolean = false,
    val trustedPeers: List<TrustedPeer> = emptyList(),
)
