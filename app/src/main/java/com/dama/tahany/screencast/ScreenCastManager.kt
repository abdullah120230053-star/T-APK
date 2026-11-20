package com.dama.tahany.screencast

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Encodes the Phone's screen to an H.264 video stream via MediaCodec and MediaProjection.
 */
class ScreenCastManager(
    private val mediaProjection: MediaProjection,
    private val scope: CoroutineScope,
    private val onPacket: (ByteArray, Boolean) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var pumpJob: Job? = null
    @Volatile
    private var isRunning = false

    fun start(width: Int = 1280, height: Int = 720, dpi: Int = 320, bitrate: Int = 2_500_000) {
        if (isRunning) return
        isRunning = true

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second keyframe interval
            }

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()
            codec = encoder

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "TahanyScreenCast",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null,
            )

            pumpJob = scope.launch(Dispatchers.IO) {
                pumpOutput(encoder)
            }
            Log.d(TAG, "Screen cast started: ${width}x${height} @ 30fps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen cast", e)
            stop()
        }
    }

    private fun pumpOutput(encoder: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isRunning && scope.isActive) {
            val outputIndex = try {
                encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
            } catch (e: Exception) {
                Log.e(TAG, "Codec dequeue error", e)
                break
            }

            if (outputIndex >= 0) {
                val outputBuffer: ByteBuffer? = encoder.getOutputBuffer(outputIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    val chunk = ByteArray(bufferInfo.size)
                    outputBuffer.get(chunk)

                    val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    onPacket(chunk, isKeyFrame)
                }
                encoder.releaseOutputBuffer(outputIndex, false)
            }
        }
    }

    fun stop() {
        isRunning = false
        pumpJob?.cancel()
        pumpJob = null

        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (_: Exception) {}

        try {
            codec?.stop()
            codec?.release()
            codec = null
        } catch (_: Exception) {}

        try {
            mediaProjection.stop()
        } catch (_: Exception) {}

        Log.d(TAG, "Screen cast stopped")
    }

    companion object {
        private const val TAG = "TahanyScreenCast"
    }
}
