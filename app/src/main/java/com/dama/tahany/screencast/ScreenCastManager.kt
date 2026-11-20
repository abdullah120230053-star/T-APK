package com.dama.tahany.screencast

import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.DataOutputStream

internal class ScreenCastManager(private val projection: MediaProjection) {
    suspend fun encode(width: Int, height: Int, dpi: Int, output: DataOutputStream) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            try {
                codec.start()
                val display = projection.createVirtualDisplay(
                    "Tahany", width, height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface, null, null,
                ) ?: error("Android could not create a capture display.")
                try {
                    val info = MediaCodec.BufferInfo()
                    while (currentCoroutineContext().isActive) {
                        val index = codec.dequeueOutputBuffer(info, 10_000L)
                        if (index < 0) continue
                        try {
                            val buffer = codec.getOutputBuffer(index)
                            if (buffer != null && info.size in 1..MAX_FRAME_BYTES) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val packet = ByteArray(info.size)
                                buffer.get(packet)
                                output.writeInt(packet.size)
                                output.writeLong(info.presentationTimeUs)
                                output.writeInt(info.flags and FRAME_FLAGS)
                                output.write(packet)
                                output.flush()
                            }
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                } finally {
                    display.release()
                }
            } finally {
                surface.release()
            }
        } finally {
            codec.release()
        }
    }
}

internal const val MAX_FRAME_BYTES = 2 * 1024 * 1024
internal const val FRAME_FLAGS = MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_KEY_FRAME
