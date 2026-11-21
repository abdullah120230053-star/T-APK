package com.dama.tahany.protocol

import com.dama.tahany.model.DeviceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtocolCodecTest {
    private val nonce = ByteArray(16) { it.toByte() }

    @Test
    fun helloRoundTrips() {
        val message = TahanyMessage.Hello(
            deviceId = "device-a",
            deviceName = "Honor Pad X9a",
            role = DeviceRole.TABLET,
            nonce = PairingCode.encodeNonce(nonce),
            appVersion = "0.1.0",
        )

        val decoded = ProtocolCodec.decode(ProtocolCodec.encode(message)) as TahanyMessage.Hello

        assertEquals("device-a", decoded.deviceId)
        assertEquals("Honor Pad X9a", decoded.deviceName)
        assertEquals(DeviceRole.TABLET, decoded.role)
        assertEquals(PROTOCOL_VERSION, decoded.v)
    }

    @Test
    fun clipboardRoundTrips() {
        val message = TahanyMessage.ClipboardUpdate(text = "hello from the phone", timestamp = 1234L)
        val decoded = ProtocolCodec.decode(ProtocolCodec.encode(message)) as TahanyMessage.ClipboardUpdate

        assertEquals("hello from the phone", decoded.text)
        assertEquals(1234L, decoded.timestamp)
        assertTrue(decoded.id.isNotBlank())
    }

    @Test
    fun oversizedMessageIsRejectedOnEncode() {
        val message = TahanyMessage.ClipboardUpdate(
            text = "x".repeat(MAX_CONTROL_MESSAGE_BYTES),
            timestamp = 1L,
        )
        try {
            ProtocolCodec.encode(message)
            fail("expected ProtocolException")
        } catch (expected: ProtocolException) {
            assertTrue(expected.message.orEmpty().contains("limit"))
        }
    }

    @Test
    fun oversizedMessageIsRejectedOnDecode() {
        try {
            ProtocolCodec.decode(ByteArray(MAX_CONTROL_MESSAGE_BYTES + 1) { 'x'.code.toByte() })
            fail("expected ProtocolException")
        } catch (expected: ProtocolException) {
            assertTrue(expected.message.orEmpty().contains("limit"))
        }
    }

    @Test
    fun malformedJsonIsRejected() {
        try {
            ProtocolCodec.decode("{\"type\":\"hello\"}".toByteArray())
            fail("expected ProtocolException")
        } catch (expected: ProtocolException) {
            assertTrue(expected.message.orEmpty().contains("not a known"))
        }
    }

    @Test
    fun unknownTypeIsRejected() {
        try {
            ProtocolCodec.decode("{\"type\":\"dance\",\"v\":1,\"id\":\"x\"}".toByteArray())
            fail("expected ProtocolException")
        } catch (expected: ProtocolException) {
            assertTrue(expected.message.orEmpty().contains("not a known"))
        }
    }

    @Test
    fun wrongProtocolVersionIsRejected() {
        val wrong = """
            {"type":"ping","v":99,"id":"x","timestamp":1}
        """.trimIndent()
        try {
            ProtocolCodec.decode(wrong.toByteArray())
            fail("expected ProtocolException")
        } catch (expected: ProtocolException) {
            assertTrue(expected.message.orEmpty().contains("protocol version"))
        }
    }

    @Test
    fun invalidInputReturnsNullThroughSafeDecoder() {
        assertNull(ProtocolCodec.decodeOrNull("not json".toByteArray()))
    }

    @Test
    fun notificationEventAndReplyRoundTrip() {
        val event = TahanyMessage.NotificationEvent(
            key = "0|com.whatsapp|1|null|1000",
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            title = "John Doe",
            text = "Are you available?",
            postedAt = 1700000000L,
            hasReply = true,
            canOpen = true,
        )

        val decodedEvent = ProtocolCodec.decode(ProtocolCodec.encode(event)) as TahanyMessage.NotificationEvent
        assertEquals("com.whatsapp", decodedEvent.packageName)
        assertEquals("WhatsApp", decodedEvent.appLabel)
        assertEquals("Are you available?", decodedEvent.text)
        assertTrue(decodedEvent.hasReply)

        val reply = TahanyMessage.NotificationReply(
            key = event.key,
            text = "Yes, calling now!",
        )
        val decodedReply = ProtocolCodec.decode(ProtocolCodec.encode(reply)) as TahanyMessage.NotificationReply
        assertEquals(event.key, decodedReply.key)
        assertEquals("Yes, calling now!", decodedReply.text)
    }

    @Test
    fun callEventAndCommandRoundTrip() {
        val event = TahanyMessage.CallEvent(
            state = CallState.RINGING,
            number = "+15550192834",
            contactName = "Mom",
            timestamp = 1700000000L,
        )

        val decodedEvent = ProtocolCodec.decode(ProtocolCodec.encode(event)) as TahanyMessage.CallEvent
        assertEquals(CallState.RINGING, decodedEvent.state)
        assertEquals("+15550192834", decodedEvent.number)
        assertEquals("Mom", decodedEvent.contactName)

        val command = TahanyMessage.CallCommand(action = CallAction.ANSWER)
        val decodedCommand = ProtocolCodec.decode(ProtocolCodec.encode(command)) as TahanyMessage.CallCommand
        assertEquals(CallAction.ANSWER, decodedCommand.action)
    }

    @Test
    fun fileAndScreenshotMetadataPreservePayloadIds() {
        val hash = "8".repeat(64)
        val offer = TahanyMessage.FileOffer(
            transferId = "file", name = "example.txt", mimeType = "text/plain",
            size = 64, sha256 = hash, payloadId = Long.MIN_VALUE,
        )
        val screenshot = TahanyMessage.ScreenshotNotice(
            sha256 = hash, name = "example.png", size = 128, takenAt = 1000,
            payloadId = Long.MAX_VALUE, transferId = "screenshot",
        )

        assertEquals(offer, ProtocolCodec.decode(ProtocolCodec.encode(offer)))
        assertEquals(screenshot, ProtocolCodec.decode(ProtocolCodec.encode(screenshot)))
    }

    @Test
    fun pairingRevealMustMatchCommittedNonce() {
        val nonce = PairingCode.newNonce()
        val different = nonce.clone().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        val commitment = PairingCode.commitNonce(nonce)
        val reveal = TahanyMessage.PairReveal(nonce = PairingCode.encodeNonce(nonce))

        assertEquals(reveal, ProtocolCodec.decode(ProtocolCodec.encode(reveal)))
        assertTrue(PairingCode.matchesCommitment(nonce, commitment))
        assertTrue(!PairingCode.matchesCommitment(different, commitment))
    }

    @Test
    fun pairingCodeDependsOnHostCertificate() {
        val first = ByteArray(16) { 1 }
        val second = ByteArray(16) { 2 }
        val channel = byteArrayOf(10, 20, 30)
        val phoneCode = PairingCode.derive("phone", "tablet", first, second, channel)
        val tabletCode = PairingCode.derive("tablet", "phone", second, first, channel)
        assertEquals(phoneCode, tabletCode)
        assertTrue(phoneCode != PairingCode.derive("phone", "tablet", first, second, byteArrayOf(30, 20, 10)))
    }

    @Test
    fun screenCastAndTouchRoundTrip() {
        val offer = TahanyMessage.ScreenCastOffer(
            width = 1920,
            height = 1080,
            fps = 30,
            bitrate = 4_000_000,
        )
        val decodedOffer = ProtocolCodec.decode(ProtocolCodec.encode(offer)) as TahanyMessage.ScreenCastOffer
        assertEquals(1920, decodedOffer.width)
        assertEquals(1080, decodedOffer.height)

        val touch = TahanyMessage.TouchEvent(
            xRatio = 0.5f,
            yRatio = 0.8f,
            action = 0,
        )
        val decodedTouch = ProtocolCodec.decode(ProtocolCodec.encode(touch)) as TahanyMessage.TouchEvent
        assertEquals(0.5f, decodedTouch.xRatio, 0.001f)
        assertEquals(0.8f, decodedTouch.yRatio, 0.001f)
        assertEquals(0, decodedTouch.action)
    }
}
