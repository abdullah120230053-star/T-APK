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
}
