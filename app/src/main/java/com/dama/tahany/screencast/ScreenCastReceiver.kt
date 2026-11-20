package com.dama.tahany.screencast

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.DataInputStream

internal class ScreenCastReceiver {
    @Volatile var surface: Surface? = null

    suspend fun decode(width: Int, height: Int, input: DataInputStream) {
        var currentSurface: Surface? = null
        var codec: MediaCodec? = null
        var config: ByteArray? = null
        var needsKeyFrame = true
        val outputInfo = MediaCodec.BufferInfo()
        try {
            while (currentCoroutineContext().isActive) {
                val size = input.readInt()
                require(size in 1..MAX_FRAME_BYTES) { "Invalid screen frame size." }
                val pts = input.readLong()
                val flags = input.readInt() and FRAME_FLAGS
                val bytes = ByteArray(size).also(input::readFully)
                if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    config = bytes
                    continue
                }

                val target = surface?.takeIf { it.isValid }
                if (target !== currentSurface) {
                    codec?.release()
                    codec = null
                    currentSurface = target
                    needsKeyFrame = true
                }
                if (target == null) continue

                if (needsKeyFrame && flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) continue
                if (codec == null) {
                    val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    codec = decoder
                    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                    decoder.configure(format, target, null, 0)
                    decoder.start()
                    config?.let { queue(decoder, it, 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) }
                }
                try {
                    drain(codec, outputInfo)
                    if (queue(codec, bytes, pts, flags)) needsKeyFrame = false
                    else needsKeyFrame = true
                    drain(codec, outputInfo)
                } catch (error: IllegalStateException) {
                    if (surface === target && target.isValid) throw error
                    codec.release()
                    codec = null
                    currentSurface = null
                    needsKeyFrame = true
                }
            }
        } finally {
            codec?.release()
        }
    }

    private fun queue(codec: MediaCodec, bytes: ByteArray, pts: Long, flags: Int): Boolean {
        val index = codec.dequeueInputBuffer(10_000L)
        if (index < 0) return false
        val buffer = checkNotNull(codec.getInputBuffer(index))
        require(bytes.size <= buffer.capacity()) { "Screen frame exceeds decoder capacity." }
        buffer.clear()
        buffer.put(bytes)
        codec.queueInputBuffer(index, 0, bytes.size, pts, flags)
        return true
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        var index = codec.dequeueOutputBuffer(info, 0)
        while (index >= 0) {
            codec.releaseOutputBuffer(index, true)
            index = codec.dequeueOutputBuffer(info, 0)
        }
    }
}
