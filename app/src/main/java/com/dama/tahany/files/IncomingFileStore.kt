package com.dama.tahany.files

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.dama.tahany.util.FileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class SavedFile(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val isImage: Boolean,
    val localPath: String?,
)

/**
 * Received files land in one predictable album/folder:
 * Pictures/Tahany for images, Downloads/Tahany for everything else. The
 * screenshot watcher never watches Pictures/Tahany, which is the first half of
 * loop prevention; the SHA-256 ledger is the second half.
 */
class IncomingFileStore(private val context: Context) {

    suspend fun save(file: File, requestedName: String, mimeType: String): SavedFile =
        withContext(Dispatchers.IO) {
            val name = FileNames.sanitize(requestedName)
            val mime = mimeType.ifBlank { mimeFor(name) }
            val isImage = mime.startsWith("image/")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveScoped(file, name, mime, isImage)
            } else {
                saveLegacy(file, name, mime, isImage)
            }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveScoped(file: File, name: String, mime: String, isImage: Boolean): SavedFile {
        val resolver = context.contentResolver
        val collection = if (isImage) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = if (isImage) {
            "${Environment.DIRECTORY_PICTURES}/Tahany"
        } else {
            "${Environment.DIRECTORY_DOWNLOADS}/Tahany"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore refused to create $name")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("No output stream for $uri")
            val done = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            return SavedFile(uri, name, mime, isImage, null)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    private fun saveLegacy(file: File, name: String, mime: String, isImage: Boolean): SavedFile {
        val root = if (isImage) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }
        val directory = File(root, "Tahany").apply { mkdirs() }
        val target = FileNames.uniqueIn(directory, name)
        file.copyTo(target, overwrite = false)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf(mime),
            null,
        )
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            target,
        )
        return SavedFile(uri, target.name, mime, isImage, target.absolutePath)
    }

    private fun mimeFor(name: String): String =
        MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(FileNames.extension(name))
            ?: "application/octet-stream"
}
