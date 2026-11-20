package com.dama.tahany.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Hashing {
    fun sha256(file: File): String = file.inputStream().use(::sha256)

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

}

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    val digits = "0123456789abcdef"
    for (index in indices) {
        val value = this[index].toInt() and 0xff
        out[index * 2] = digits[value ushr 4]
        out[index * 2 + 1] = digits[value and 0x0f]
    }
    return String(out)
}
