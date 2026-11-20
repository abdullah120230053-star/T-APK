package com.dama.tahany.screencast

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Decodes incoming H.264 video packets on the Tablet (Honor Pad X9a) and renders to a Surface.
 */
class ScreenCastReceiver(
    private val surface: Surface,
    private val width: Int = 1280,
    private val height: Int = 720,
) {
    private var decoder: MediaCodec? = null
    @Volatile
    private var isConfigured = false

    fun start() {
        if (isConfigured) return
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, surface, null, 0)
            codec.start()
            decoder = codec
            isConfigured = true
            Log.d(TAG, "Screen cast receiver initialized with Surface: ${width}x$height")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize decoder", e)
            stop()
        }
    }

    fun feedPacket(packet: ByteArray, presentationTimeUs: Long = System.nanoTime() / 1000) {
        val codec = decoder ?: return
        if (!isConfigured) return

        try {
            val inputIndex = codec.dequeueInputBuffer(10_000L)
            if (inputIndex >= 0) {
                val inputBuffer: ByteBuffer? = codec.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(packet)
                    codec.queueInputBuffer(inputIndex, 0, packet.size, presentationTimeUs, 0)
                }
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
            while (outputIndex >= 0) {
                // Render directly to surface
                codec.releaseOutputBuffer(outputIndex, true)
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding frame", e)
        }
    }

    fun stop() {
        isConfigured = false
        try {
            decoder?.stop()
            decoder?.release()
            decoder = null
        } catch (_: Exception) {}
        Log.d(TAG, "Screen cast receiver stopped")
    }

    companion object {
        private const val TAG = "ScreenCastReceiver"
    }
}
