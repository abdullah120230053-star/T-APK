package com.dama.tahany.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.dama.tahany.link.LinkController
import com.dama.tahany.model.LinkPhase
import com.dama.tahany.util.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Automatic clipboard sync is foreground-only. Android 10+ blocks background
 * clipboard reads, and pretending otherwise creates a feature that silently
 * fails. The quick-settings tile and the send button are the manual paths.
 */
class ClipboardSyncManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val link: LinkController,
) : DefaultLifecycleObserver {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private var pollJob: Job? = null
    private var foreground = false
    private var lastHash: String? = null

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        foreground = true
        lastHash = readClipboard()?.let(::hash)
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (!foreground) continue
                if (link.state.value.phase != LinkPhase.CONNECTED) continue
                pollOnce()
            }
        }
    }

    fun stop() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        pollJob?.cancel()
        pollJob = null
    }

    override fun onStart(owner: LifecycleOwner) {
        foreground = true
        // Do not send an old clipboard simply because the app came forward.
        lastHash = readClipboard()?.let(::hash)
    }

    override fun onStop(owner: LifecycleOwner) {
        foreground = false
    }

    fun sendNow() {
        mainHandler.post {
            val text = readClipboard() ?: return@post
            lastHash = hash(text)
            link.sendClipboard(text)
        }
    }

    /** Called when the other device sends its clipboard. */
    fun applyRemote(text: String) {
        mainHandler.post {
            if (text.isBlank()) return@post
            lastHash = hash(text)
            clipboard?.setPrimaryClip(ClipData.newPlainText("Tahany", text))
        }
    }

    private fun pollOnce() {
        mainHandler.post {
            val text = readClipboard() ?: return@post
            val current = hash(text)
            if (current == lastHash) return@post
            lastHash = current
            link.sendClipboard(text)
        }
    }

    private fun readClipboard(): String? {
        val clip = clipboard?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(context)?.toString()?.trim().orEmpty()
        return if (text.isBlank()) null else text.take(MAX_CHARS)
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .toHex()

    private companion object {
        const val POLL_INTERVAL_MS = 1_500L
        const val MAX_CHARS = 8_000
    }
}
