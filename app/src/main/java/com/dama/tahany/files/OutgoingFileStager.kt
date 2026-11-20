package com.dama.tahany.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.dama.tahany.util.FileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal const val MAX_TRANSFER_BYTES = 2L * 1024 * 1024 * 1024

data class StagedFile(
    val file: File,
    val name: String,
    val mimeType: String,
)

class OutgoingFileStager(private val context: Context) {
    suspend fun stage(uri: Uri): StagedFile? = withContext(Dispatchers.IO) {
        val name = FileNames.sanitize(queryName(uri) ?: "shared-file")
        val directory = File(context.cacheDir, "shared/${UUID.randomUUID()}")
        val target = File(directory, name)
        var staged = false
        try {
            val mime = context.contentResolver.getType(uri)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(FileNames.extension(name))
                ?: "application/octet-stream"
            check(directory.mkdirs())
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
            input.use { source ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var bytes = 0L
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        bytes += read
                        check(bytes <= MAX_TRANSFER_BYTES) { "File exceeds the transfer limit." }
                        output.write(buffer, 0, read)
                    }
                }
            }
            staged = true
            StagedFile(target, name, mime)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            if (!staged) {
                target.delete()
                directory.delete()
            }
        }
    }

    fun cleanupOlderThan(maxAgeMs: Long = 24L * 60L * 60L * 1000L) {
        val directory = File(context.cacheDir, "shared")
        val cutoff = System.currentTimeMillis() - maxAgeMs
        directory.walkBottomUp().forEach { file ->
            if (file != directory && file.lastModified() < cutoff) file.delete()
        }
    }

    private fun queryName(uri: Uri): String? {
        var name: String? = null
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
        return name
    }
}
