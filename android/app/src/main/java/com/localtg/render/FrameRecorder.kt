package com.localtg.render

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import com.localtg.AppLog

/**
 * 把渲染器"实际上屏的每一帧"编码成 mp4(自动化测试用,见 AutoTestActivity):渲染器在每次画到屏幕之后,再画一遍到 [surface],
 * 帧的时间戳 = 这一帧的 vsync 时间,所以回放出来的快慢、重复、跳帧和手机屏幕上看到的一致(可变帧率)。
 */
class FrameRecorder(val path: String, val w: Int, val h: Int, bitrate: Int = 24_000_000) {
    private val codec: MediaCodec
    val surface: Surface
    private val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var muxerStarted = false
    private val drain: Thread
    @Volatile var frames = 0; private set

    init {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
        drain = Thread({ drainLoop() }, "frame-recorder").also { it.start() }
    }

    fun onFrame() { frames++ }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val i = codec.dequeueOutputBuffer(info, 100_000)
                when {
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start(); muxerStarted = true
                    }
                    i >= 0 -> {
                        val buf = codec.getOutputBuffer(i)
                        if (buf != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(i, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
        } catch (t: Throwable) {
            AppLog.w("record", "录制线程出错:${t.message}")
        }
    }

    /** 结束编码并关闭文件(阻塞到编码器吐完)。 */
    fun finish() {
        runCatching { codec.signalEndOfInputStream() }
        runCatching { drain.join(8000) }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { if (muxerStarted) muxer.stop() }
        runCatching { muxer.release() }
        runCatching { surface.release() }
        AppLog.i("record", "录制结束:$path 共 $frames 帧")
    }
}
