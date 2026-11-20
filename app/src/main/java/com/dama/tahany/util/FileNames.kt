package com.dama.tahany.util

import java.io.File

object FileNames {
    private val illegal = Regex("[\\u0000-\\u001f<>:\"/\\\\|?*]")

    /**
     * Never trust a peer-supplied display name. It becomes a filename in
     * MediaStore or the app cache, so strip separators, control characters,
     * and leading dots before truncating.
     */
    fun sanitize(raw: String, fallback: String = "file"): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = illegal.replace(base, "_")
            .trim()
            .trim('.')
            .take(120)
        return cleaned.ifBlank { fallback }
    }

    fun extension(name: String): String =
        name.substringAfterLast('.', "").lowercase().take(12)

    fun withSuffix(name: String, suffix: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name$suffix" else name.substring(0, dot) + suffix + name.substring(dot)
    }

    fun uniqueIn(directory: File, requestedName: String): File {
        val safe = sanitize(requestedName)
        var candidate = File(directory, safe)
        if (!candidate.exists()) return candidate
        val dot = safe.lastIndexOf('.')
        val stem = if (dot <= 0) safe else safe.substring(0, dot)
        val extension = if (dot <= 0) "" else safe.substring(dot)
        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "$stem ($index)$extension")
            index += 1
        }
        return candidate
    }
}
