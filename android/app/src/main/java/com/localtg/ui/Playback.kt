package com.localtg.ui

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.localtg.AppContainer
import com.localtg.data.AppSettings

/** 解码器偏好选项(设置页与播放器共用)。 */
val DECODER_MODES = listOf(
    "auto" to "自动(系统默认顺序)",
    "hw_first" to "硬件解码优先",
    "sw_first" to "软件解码优先",
    "hw_only" to "仅硬件解码",
    "sw_only" to "仅软件解码",
)

private fun isSoftware(i: MediaCodecInfo) =
    i.softwareOnly || i.name.startsWith("c2.android.") || i.name.startsWith("OMX.google.")

/** 按偏好给解码器排序或过滤;mode 为 auto 时保持系统顺序。 */
fun codecSelector(mode: String): MediaCodecSelector = MediaCodecSelector { mime, secure, tunneling ->
    val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
    when (mode) {
        "hw_first" -> all.sortedBy { if (isSoftware(it)) 1 else 0 }
        "sw_first" -> all.sortedBy { if (isSoftware(it)) 0 else 1 }
        "hw_only" -> all.filter { !isSoftware(it) }
        "sw_only" -> all.filter { isSoftware(it) }
        else -> all
    }
}

/** 按设置创建播放器。forceSoftware = 上一次硬解失败后的自动重试。 */
fun createPlayer(ctx: Context, c: AppContainer, s: AppSettings, forceSoftware: Boolean, modeOverride: String? = null): ExoPlayer {
    val renderers = DefaultRenderersFactory(ctx).apply {
        setEnableDecoderFallback(s.decoderFallback)
        val mode = modeOverride ?: if (forceSoftware && s.decoderMode != "sw_only") "sw_first" else s.decoderMode
        com.localtg.AppLog.i("player", "创建播放器 解码方式=$mode 缓冲=${s.bufferMode} 异步队列=${s.asyncQueueing} 隧道=${s.tunneling}")
        setMediaCodecSelector(codecSelector(mode))
        when (s.asyncQueueing) {
            "on" -> forceEnableMediaCodecAsynchronousQueueing()
            "off" -> forceDisableMediaCodecAsynchronousQueueing()
        }
    }
    // 缓冲:最小 / 最大(毫秒)、开始播放所需、卡顿后恢复所需
    val (minMs, maxMs, startMs, rebufMs) = when (s.bufferMode) {
        "small" -> listOf(2500, 15_000, 1000, 2000)
        "large" -> listOf(30_000, 120_000, 2500, 5000)
        else -> listOf(15_000, 50_000, 2500, 5000)
    }
    val load = DefaultLoadControl.Builder().setBufferDurationsMs(minMs, maxMs, startMs, rebufMs).build()
    val selector = DefaultTrackSelector(ctx).apply {
        parameters = buildUponParameters().setTunnelingEnabled(s.tunneling).build()
    }
    val player = ExoPlayer.Builder(ctx, renderers)
        .setTrackSelector(selector)
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(c.http)))
        .setLoadControl(load)
        .setSeekBackIncrementMs(s.seekBackSec * 1000L)
        .setSeekForwardIncrementMs(s.seekForwardSec * 1000L)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(s.pauseOnUnplug)
        .build()
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
        if (s.maxHeight > 0) setMaxVideoSize(Int.MAX_VALUE, s.maxHeight)
        if (s.maxBitrateMbps > 0) setMaxVideoBitrate(s.maxBitrateMbps * 1_000_000)
        if (s.audioLanguage.isNotEmpty()) setPreferredAudioLanguage(s.audioLanguage)
        setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !s.showSubtitles)
        if (s.showSubtitles && s.subtitleLanguage.isNotEmpty()) setPreferredTextLanguage(s.subtitleLanguage)
    }.build()
    player.setPlaybackSpeed(s.speed)
    player.repeatMode = if (s.loop) androidx.media3.common.Player.REPEAT_MODE_ONE else androidx.media3.common.Player.REPEAT_MODE_OFF
    player.volume = if (s.muted) 0f else 1f
    return player
}

// ---------------------------------------------------------------- 本机解码器能力

data class CodecRow(val name: String, val hardware: Boolean, val detail: String)
data class CodecGroup(val label: String, val mime: String, val decoders: List<CodecRow>)

private val PROBE = listOf(
    "H.264 / AVC" to "video/avc",
    "H.265 / HEVC" to "video/hevc",
    "AV1" to "video/av01",
    "VP9" to "video/x-vnd.on2.vp9",
    "VP8" to "video/x-vnd.on2.vp8",
    "MPEG-4 Part 2" to "video/mp4v-es",
    "MPEG-2" to "video/mpeg2",
    "H.263" to "video/3gpp",
    "杜比视界" to "video/dolby-vision",
    "AAC" to "audio/mp4a-latm",
    "MP3" to "audio/mpeg",
    "杜比数字 AC-3" to "audio/ac3",
    "杜比数字+ E-AC-3" to "audio/eac3",
    "DTS" to "audio/vnd.dts",
    "Opus" to "audio/opus",
    "FLAC" to "audio/flac",
    "Vorbis" to "audio/vorbis",
)

/** 查询本机系统里每种格式有哪些解码器、是不是硬件解码、最大分辨率。 */
fun probeDeviceCodecs(): List<CodecGroup> {
    val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
    return PROBE.map { (label, mime) ->
        val rows = infos.filter { info -> info.supportedTypes.any { it.equals(mime, true) } }.map { info ->
            val sw = if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
            val detail = caps?.videoCapabilities?.let { v ->
                val w = v.supportedWidths.upper
                val h = v.supportedHeights.upper
                val fps = runCatching { v.getSupportedFrameRatesFor(minOf(w, 1920), minOf(h, 1080)).upper.toInt() }.getOrDefault(0)
                "最大 ${w}×${h}" + if (fps > 0) ",1080p 最高 ${fps} 帧" else ""
            } ?: caps?.audioCapabilities?.let { a -> "最多 ${a.maxInputChannelCount} 声道" }.orEmpty()
            CodecRow(info.name, !sw, detail)
        }
        CodecGroup(label, mime, rows)
    }
}
