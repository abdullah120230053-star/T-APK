package com.dama.tahany.protocol

import com.dama.tahany.model.DeviceRole
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID

const val PROTOCOL_VERSION = 3
const val TOUCH_TAP = 0
const val TOUCH_SWIPE = 1
const val MAX_CONTROL_MESSAGE_BYTES = 32 * 1024

@Serializable
enum class CallState {
    RINGING,
    OFFHOOK,
    IDLE,
}

@Serializable
enum class CallAction {
    ANSWER,
    DECLINE,
    SPEAKER_ON,
    SPEAKER_OFF,
}

/**
 * Every small control message on the link. Large content (files, encoded
 * video) never goes through this type: Nearby file/stream payloads carry it.
 *
 * `v` is checked by [ProtocolCodec]; adding a message type is a protocol
 * change and should not silently change the meaning of an existing one.
 */
@Serializable
sealed class TahanyMessage {
    abstract val v: Int
    abstract val id: String

    @Serializable
    @SerialName("hello")
    data class Hello(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val deviceId: String,
        val deviceName: String,
        val role: DeviceRole,
        val nonce: String,
        val appVersion: String,
    ) : TahanyMessage()

    @Serializable
    @SerialName("hello_ack")
    data class HelloAck(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val deviceId: String,
        val deviceName: String,
        val role: DeviceRole,
        val nonce: String,
        val appVersion: String,
    ) : TahanyMessage()

    @Serializable
    @SerialName("pair_reveal")
    data class PairReveal(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val nonce: String,
    ) : TahanyMessage()

    /** Sent after the user says both six-digit codes match. */
    @Serializable
    @SerialName("pair_confirm")
    data class PairConfirm(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val proof: String,
    ) : TahanyMessage()

    @Serializable
    @SerialName("pair_rejected")
    data class PairRejected(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val reason: String? = null,
    ) : TahanyMessage()

    @Serializable
    @SerialName("clipboard")
    data class ClipboardUpdate(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val text: String,
        val timestamp: Long,
    ) : TahanyMessage()

    @Serializable
    @SerialName("file_offer")
    data class FileOffer(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val transferId: String,
        val name: String,
        val mimeType: String,
        val size: Long,
        val sha256: String,
        val payloadId: Long,
    ) : TahanyMessage()

    @Serializable
    @SerialName("file_result")
    data class FileResult(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val transferId: String,
        val accepted: Boolean,
        val savedName: String? = null,
        val error: String? = null,
    ) : TahanyMessage()

    @Serializable
    @SerialName("screenshot")
    data class ScreenshotNotice(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val sha256: String,
        val name: String,
        val size: Long,
        val takenAt: Long,
        val payloadId: Long,
        val transferId: String,
    ) : TahanyMessage()

    @Serializable
    @SerialName("notification")
    data class NotificationEvent(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val key: String,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val postedAt: Long,
        val hasReply: Boolean,
        val canOpen: Boolean,
    ) : TahanyMessage()

    @Serializable
    @SerialName("notification_reply")
    data class NotificationReply(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val key: String,
        val text: String,
    ) : TahanyMessage()

    @Serializable
    @SerialName("notification_action")
    data class NotificationAction(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val key: String,
        val actionIndex: Int,
    ) : TahanyMessage()

    @Serializable
    @SerialName("call")
    data class CallEvent(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val state: CallState,
        val number: String? = null,
        val contactName: String? = null,
        val timestamp: Long,
    ) : TahanyMessage()

    @Serializable
    @SerialName("call_command")
    data class CallCommand(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val action: CallAction,
    ) : TahanyMessage()

    @Serializable
    @SerialName("call_result")
    data class CallResult(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val action: CallAction,
        val succeeded: Boolean,
    ) : TahanyMessage()

    @Serializable
    @SerialName("screencast_offer")
    data class ScreenCastOffer(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val width: Int = 1280,
        val height: Int = 720,
        val fps: Int = 30,
        val bitrate: Int = 2_500_000,
    ) : TahanyMessage()

    @Serializable
    @SerialName("screencast_answer")
    data class ScreenCastAnswer(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val accepted: Boolean,
        val reason: String? = null,
    ) : TahanyMessage()

    @Serializable
    @SerialName("screencast_stop")
    data class ScreenCastStop(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val reason: String? = null,
    ) : TahanyMessage()

    @Serializable
    @SerialName("touch_event")
    data class TouchEvent(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val xRatio: Float,
        val yRatio: Float,
        val action: Int,
        val endXRatio: Float = xRatio,
        val endYRatio: Float = yRatio,
    ) : TahanyMessage()

    @Serializable
    @SerialName("ping")
    data class Ping(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val timestamp: Long,
    ) : TahanyMessage()

    @Serializable
    @SerialName("pong")
    data class Pong(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val timestamp: Long,
    ) : TahanyMessage()

    @Serializable
    @SerialName("error")
    data class ErrorMessage(
        override val v: Int = PROTOCOL_VERSION,
        override val id: String = newMessageId(),
        val code: String,
        val message: String,
    ) : TahanyMessage()
}

fun newMessageId(): String = UUID.randomUUID().toString()

class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

object ProtocolCodec {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(message: TahanyMessage): ByteArray {
        val text = json.encodeToString(TahanyMessage.serializer(), message)
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_CONTROL_MESSAGE_BYTES) {
            throw ProtocolException(
                "control message is ${bytes.size} bytes; the limit is $MAX_CONTROL_MESSAGE_BYTES",
            )
        }
        return bytes
    }

    fun decode(bytes: ByteArray): TahanyMessage {
        if (bytes.size > MAX_CONTROL_MESSAGE_BYTES) {
            throw ProtocolException(
                "control message is ${bytes.size} bytes; the limit is $MAX_CONTROL_MESSAGE_BYTES",
            )
        }
        val text = try {
            bytes.toString(Charsets.UTF_8)
        } catch (error: Exception) {
            throw ProtocolException("control message is not valid UTF-8", error)
        }
        val message = try {
            json.decodeFromString(TahanyMessage.serializer(), text)
        } catch (error: SerializationException) {
            throw ProtocolException("control message is not a known Tahany message", error)
        } catch (error: IllegalArgumentException) {
            throw ProtocolException("control message is malformed", error)
        }
        if (message.v != PROTOCOL_VERSION) {
            throw ProtocolException(
                "unsupported protocol version ${message.v}; this build speaks $PROTOCOL_VERSION",
            )
        }
        return message
    }

    fun decodeOrNull(bytes: ByteArray): TahanyMessage? =
        try {
            decode(bytes)
        } catch (_: ProtocolException) {
            null
        }
}
