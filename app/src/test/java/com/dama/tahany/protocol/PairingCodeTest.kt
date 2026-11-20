package com.dama.tahany.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {
    private val phoneId = "phone-1111"
    private val tabletId = "tablet-2222"
    private val phoneNonce = ByteArray(16) { (it + 1).toByte() }
    private val tabletNonce = ByteArray(16) { (it + 101).toByte() }

    @Test
    fun bothSidesDeriveTheSameSixDigitCode() {
        val phone = PairingCode.derive(phoneId, tabletId, phoneNonce, tabletNonce)
        val tablet = PairingCode.derive(tabletId, phoneId, tabletNonce, phoneNonce)

        assertEquals(phone, tablet)
        assertTrue(phone.matches(Regex("\\d{6}")))
    }

    @Test
    fun differentNonceProducesDifferentCode() {
        val original = PairingCode.derive(phoneId, tabletId, phoneNonce, tabletNonce)
        val tampered = PairingCode.derive(
            phoneId,
            tabletId,
            phoneNonce,
            tabletNonce.copyOf().also { it[0] = (it[0] + 1).toByte() },
        )
        assertFalse(original == tampered)
    }

    @Test
    fun proofVerifiesOnlyForTheSameMaterialAndCode() {
        val sas = PairingCode.derive(phoneId, tabletId, phoneNonce, tabletNonce)
        val proof = PairingCode.proof(phoneId, tabletId, phoneNonce, tabletNonce, sas)

        assertTrue(PairingCode.verifyProof(proof, proof))
        assertTrue(PairingCode.verifyProof(proof, proof.uppercase()))
        assertFalse(PairingCode.verifyProof(proof, "0".repeat(64)))
    }

    @Test
    fun nonceRoundTripsThroughUrlSafeBase64() {
        val encoded = PairingCode.encodeNonce(phoneNonce)
        val decoded = PairingCode.decodeNonce(encoded)
        assertNotNull(decoded)
        assertTrue(phoneNonce.contentEquals(decoded))
        assertNull(PairingCode.decodeNonce("not base64!"))
    }

    @Test
    fun fixedVectorIsStable() {
        // Changing this value is a protocol change: both installed builds must
        // derive the same code from the same inputs.
        val sas = PairingCode.derive(
            localDeviceId = "00000000-0000-0000-0000-000000000001",
            remoteDeviceId = "00000000-0000-0000-0000-000000000002",
            localNonce = ByteArray(16) { 0x01 },
            remoteNonce = ByteArray(16) { 0x02 },
        )
        assertEquals("913888", sas)
    }
}
