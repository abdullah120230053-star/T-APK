package com.dama.tahany.screenshot

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class ScreenshotCandidate(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val relativePath: String?,
    val dateAdded: Long,
    val size: Long,
)

/**
 * Watches MediaStore for new images whose path or name identifies them as
 * screenshots. It never watches Tahany's own receive folder, and it debounces
 * the MediaStore notification so it does not hash a half-written image.
 */
class ScreenshotWatcher(
    private val context: Context,
    private val onScreenshot: (ScreenshotCandidate) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val seenIds = LinkedHashSet<Long>()
    private var scanJob: Job? = null
    private var registered = false
    private var lastScanSeconds = 0L

    private val observer = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scheduleScan()
        }
    }

    fun start() {
        if (registered) return
        registered = true
        lastScanSeconds = (System.currentTimeMillis() / 1000L) - 5L
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
        scheduleScan()
    }

    fun stop() {
        if (registered) {
            context.contentResolver.unregisterContentObserver(observer)
            registered = false
        }
        scanJob?.cancel()
        scanJob = null
    }

    fun refresh() {
        scheduleScan()
    }

    private fun scheduleScan() {
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(900L)
            scan()
        }
    }

    private suspend fun scan() {
        if (!hasImagePermission()) return
        val now = System.currentTimeMillis() / 1000L
        val since = if (lastScanSeconds > 0) lastScanSeconds - 10L else now - 120L
        lastScanSeconds = now

        val api29 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.SIZE)
            if (api29) add(MediaStore.Images.Media.RELATIVE_PATH) else add(MediaStore.Images.Media.DATA)
        }.toTypedArray()

        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val candidates = mutableListOf<ScreenshotCandidate>()

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(since.toString()),
                sort,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val pathColumn = cursor.getColumnIndex(
                    if (api29) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA,
                )
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    if (seenIds.contains(id)) continue
                    val name = cursor.getString(nameColumn) ?: continue
                    val path = if (pathColumn >= 0) cursor.getString(pathColumn) else null
                    if (!looksLikeScreenshot(name, path)) continue
                    seenIds.add(id)
                    while (seenIds.size > MAX_SEEN_IDS) {
                        val iterator = seenIds.iterator()
                        if (iterator.hasNext()) {
                            iterator.next()
                            iterator.remove()
                        }
                    }
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id,
                    )
                    candidates += ScreenshotCandidate(
                        id = id,
                        uri = uri,
                        displayName = name,
                        relativePath = path,
                        dateAdded = cursor.getLong(dateColumn),
                        size = cursor.getLong(sizeColumn),
                    )
                }
            }
        } catch (_: SecurityException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }

        candidates.forEach(onScreenshot)
    }

    private fun looksLikeScreenshot(name: String, path: String?): Boolean {
        if (path?.contains("tahany", ignoreCase = true) == true) return false
        if (path?.contains("screenshot", ignoreCase = true) == true) return true
        return name.startsWith("screenshot", ignoreCase = true) ||
            name.contains("screen shot", ignoreCase = true)
    }

    private fun hasImagePermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val MAX_SEEN_IDS = 200
    }
}
