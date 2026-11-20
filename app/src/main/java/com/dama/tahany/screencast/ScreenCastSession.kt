package com.dama.tahany.screencast

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Surface
import com.dama.tahany.link.LinkController
import com.dama.tahany.model.ScreenCastPhase
import com.dama.tahany.protocol.TahanyMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.math.roundToInt

/** Owns capture consent, one encoder or decoder, and the lifetime of its Nearby stream. */
internal class ScreenCastSession(
    private val link: LinkController,
    private val scope: CoroutineScope,
    private val projectionManager: MediaProjectionManager,
    private val displaySize: () -> Size,
    private val densityDpi: () -> Int,
    private val setProjectionForeground: (Boolean) -> Boolean,
) {
    private val receiver = ScreenCastReceiver()
    private var projection: MediaProjection? = null
    private var foreground = false
    private var input: InputStream? = null
    private var pipeOutput: PipedOutputStream? = null
    private var job: Job? = null
    private var timeout: Job? = null
    private var initialDisplaySize: Size? = null
    private var capturedSize: Size? = null
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() = stop()

        override fun onCapturedContentResize(width: Int, height: Int) {
            capturedSize = Size(width, height)
        }
    }

    fun startConsent(data: Intent) {
        if (!link.isConnected() || projection != null) return
        if (!setProjectionForeground(true)) {
            stop("Android could not start screen capture.")
            return
        }
        foreground = true
        try {
            val capture = checkNotNull(projectionManager.getMediaProjection(Activity.RESULT_OK, data))
            projection = capture
            capture.registerCallback(callback, Handler(Looper.getMainLooper()))
            val size = displaySize()
            initialDisplaySize = size
            val scale = minOf(1280f / maxOf(size.width, size.height), 720f / minOf(size.width, size.height))
            fun aligned(value: Int) = ((value * scale / 16).roundToInt() * 16).coerceIn(16, 1280)
            link.sendScreenCastOffer(aligned(size.width), aligned(size.height))
            awaitStream("The tablet did not accept screen mirroring.")
        } catch (_: Exception) {
            stop("Android could not start screen capture.")
        }
    }

    fun acceptOffer() {
        link.sendScreenCastAnswer(true)
        awaitStream("The phone did not start its screen stream.")
    }

    fun onAnswer(accepted: Boolean) {
        timeout?.cancel()
        val capture = projection ?: return
        if (!accepted) {
            clear()
            return
        }
        if (job?.isActive == true) return
        try {
            val pipe = PipedInputStream(256 * 1024)
            input = pipe
            val output = PipedOutputStream(pipe)
            pipeOutput = output
            val state = link.state.value.screenCast
            val dpi = densityDpi()
            link.startScreenStream(pipe) {
                if (input === pipe) stop("Could not send the screen stream.")
            }
            if (input !== pipe) return
            runStream("Screen capture stopped unexpectedly.") {
                DataOutputStream(BufferedOutputStream(output)).use {
                    ScreenCastManager(capture).encode(state.width, state.height, dpi, it)
                }
            }
        } catch (_: Exception) {
            stop("Could not start the screen stream.")
        }
    }

    fun receive(stream: InputStream) {
        if (input != null) {
            runCatching { stream.close() }
            return
        }
        timeout?.cancel()
        input = stream
        val state = link.state.value.screenCast
        link.markScreenReceiving()
        runStream("The screen stream ended.") {
            DataInputStream(BufferedInputStream(stream)).use {
                receiver.decode(state.width, state.height, it)
            }
        }
    }

    fun setSurface(surface: Surface?) {
        receiver.surface = surface
    }

    fun dispatchTouch(touch: TahanyMessage.TouchEvent) {
        val initial = initialDisplaySize ?: return
        val current = displaySize()
        if (current != initial || link.state.value.screenCast.phase != ScreenCastPhase.STREAMING) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && capturedSize != current) return
        TahanyAccessibilityService.injectTouch(touch, current)
    }

    fun stop(reason: String? = null) {
        clear()
        link.sendScreenCastStop(reason)
    }

    fun clear() {
        timeout?.cancel()
        timeout = null
        job?.cancel()
        job = null
        runCatching { input?.close() }
        input = null
        runCatching { pipeOutput?.close() }
        pipeOutput = null
        initialDisplaySize = null
        capturedSize = null
        val capture = projection
        projection = null
        if (capture != null) {
            runCatching { capture.unregisterCallback(callback) }
            runCatching { capture.stop() }
        }
        if (foreground) {
            foreground = false
            setProjectionForeground(false)
        }
    }

    private fun awaitStream(message: String) {
        timeout?.cancel()
        timeout = scope.launch {
            delay(15_000)
            timeout = null
            stop(message)
        }
    }

    private fun runStream(failure: String, process: suspend () -> Unit) {
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { process() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                stop(failure)
            }
        }
    }
}
