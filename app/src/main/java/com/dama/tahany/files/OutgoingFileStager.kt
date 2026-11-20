package com.dama.tahany.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.dama.tahany.util.FileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class StagedFile(
    val file: File,
    val name: String,
    val mimeType: String,
)

class OutgoingFileStager(private val context: Context) {
    suspend fun stage(uri: Uri): StagedFile? = withContext(Dispatchers.IO) {
        val metadata = queryMetadata(uri)
        val name = FileNames.sanitize(metadata.first ?: "shared-file")
        val mime = metadata.second
            ?: context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(FileNames.extension(name))
            ?: "application/octet-stream"
        // Keep the original filename as the payload's display name. A unique
        // per-file directory prevents collisions without prefixing the name.
        val directory = File(context.cacheDir, "shared/${UUID.randomUUID()}").apply { mkdirs() }
        val target = File(directory, name)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            if (target.length() <= 0L) {
                target.delete()
                return@withContext null
            }
            StagedFile(target, name, mime)
        } catch (_: Exception) {
            target.delete()
            null
        }
    }

    fun cleanupOlderThan(maxAgeMs: Long = 24L * 60L * 60L * 1000L) {
        val directory = File(context.cacheDir, "shared")
        val cutoff = System.currentTimeMillis() - maxAgeMs
        directory.walkBottomUp().forEach { file ->
            if (file != directory && file.lastModified() < cutoff) file.delete()
        }
    }

    private fun queryMetadata(uri: Uri): Pair<String?, String?> {
        var name: String? = null
        var mime: String? = null
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) name = cursor.getString(index)
                }
            }
        }
        mime = context.contentResolver.getType(uri)
        return name to mime
    }
}
