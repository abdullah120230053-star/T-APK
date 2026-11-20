package com.dama.tahany.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A short authentication string (SAS), not a password.
 *
 * The displayed code binds the app handshake to the Nearby session token.
 * Both users compare the code before either app exposes its data.
 */
object PairingCode {
    const val DIGITS = 6
    private const val SAS_DOMAIN = "tahany-sas-v1"
    private const val PROOF_PREFIX = "confirm:"

    private val secureRandom = SecureRandom()

    fun newNonce(): ByteArray = ByteArray(16).also(secureRandom::nextBytes)

    fun encodeNonce(nonce: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)

    fun decodeNonce(value: String): ByteArray? =
        try {
            Base64.getUrlDecoder().decode(value).takeIf { it.size == 16 }
        } catch (_: IllegalArgumentException) {
            null
        }

    fun derive(
        localDeviceId: String,
        remoteDeviceId: String,
        localNonce: ByteArray,
        remoteNonce: ByteArray,
        channelToken: ByteArray? = null,
    ): String {
        val transcript = material(localDeviceId, remoteDeviceId, localNonce, remoteNonce)
        val material = if (channelToken == null) transcript else transcript + channelToken
        val digest = hmac(SAS_DOMAIN.toByteArray(Charsets.UTF_8), material)
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (digest[index].toLong() and 0xffL)
        }
        val code = Math.floorMod(value, 1_000_000L)
        return String.format(Locale.ROOT, "%06d", code)
    }

    fun proof(
        localDeviceId: String,
        remoteDeviceId: String,
        localNonce: ByteArray,
        remoteNonce: ByteArray,
        sas: String,
    ): String {
        val material = material(localDeviceId, remoteDeviceId, localNonce, remoteNonce)
        val digest = hmac(material, (PROOF_PREFIX + sas).toByteArray(Charsets.UTF_8))
        return digest.toHex()
    }

    fun verifyProof(
        expectedProof: String,
        candidateProof: String,
    ): Boolean = MessageDigest.isEqual(
        expectedProof.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8),
        candidateProof.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8),
    )

    private fun material(
        localDeviceId: String,
        remoteDeviceId: String,
        localNonce: ByteArray,
        remoteNonce: ByteArray,
    ): ByteArray {
        val localFirst = localDeviceId <= remoteDeviceId
        val firstId = if (localFirst) localDeviceId else remoteDeviceId
        val secondId = if (localFirst) remoteDeviceId else localDeviceId
        val firstNonce = if (localFirst) localNonce else remoteNonce
        val secondNonce = if (localFirst) remoteNonce else localNonce

        return buildString {
            append(SAS_DOMAIN).append('\n')
            append(firstId).append('\n')
            append(secondId).append('\n')
            append(firstNonce.toHex()).append('\n')
            append(secondNonce.toHex())
        }.toByteArray(Charsets.UTF_8)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun ByteArray.toHex(): String {
        val out = CharArray(size * 2)
        val digits = "0123456789abcdef"
        for (index in indices) {
            val value = this[index].toInt() and 0xff
            out[index * 2] = digits[value ushr 4]
            out[index * 2 + 1] = digits[value and 0x0f]
        }
        return String(out)
    }
}
