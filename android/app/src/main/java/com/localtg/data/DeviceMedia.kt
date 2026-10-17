package com.localtg.data

import android.media.MediaCodecList

/**
 * 这台手机能播什么:用 MediaCodecList 查视频 / 音频解码器,再结合 ExoPlayer 自带的容器解析器,判断一个视频能不能直接播、
 * 以及"只是音轨 / 容器不行、视频本身能解"(这时服务端做一份只转音频的兼容版本就够了,不用整条重编码)。
 *
 * 名字用的是服务端(ffprobe)返回的编码名 / 容器名。判断偏保守:拿不准就当作"可能不行",最终仍有播放失败时的转码兜底。
 */
object DeviceMedia {
    private val videoMime = mapOf(
        "h264" to "video/avc", "hevc" to "video/hevc", "av1" to "video/av01", "vp9" to "video/x-vnd.on2.vp9", "vp8" to "video/x-vnd.on2.vp8",
        "mpeg4" to "video/mp4v-es", "mpeg2video" to "video/mpeg2", "h263" to "video/3gpp",
    )
    private val audioMime = mapOf(
        "aac" to "audio/mp4a-latm", "mp3" to "audio/mpeg", "opus" to "audio/opus", "vorbis" to "audio/vorbis", "flac" to "audio/flac",
        "ac3" to "audio/ac3", "eac3" to "audio/eac3", "dts" to "audio/vnd.dts", "truehd" to "audio/true-hd", "alac" to "audio/alac", "mp2" to "audio/mpeg-L2",
        "amr_nb" to "audio/3gpp", "amr_wb" to "audio/amr-wb",
    )

    private val decoders: Set<String> by lazy {
        runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }.flatMap { it.supportedTypes.toList() }.map { it.lowercase() }.toSet() }
            .getOrDefault(emptySet())
    }

    /** 视频编码有解码器(PCM 之类 ExoPlayer 自己处理的不在这里)。未知编码名按"不行"。 */
    fun videoOk(vcodec: String): Boolean {
        val m = videoMime[vcodec.lowercase()] ?: return false
        return m in decoders
    }

    /** 音频编码能解:PCM 系列 ExoPlayer 自带;其余查系统解码器(AAC / MP3 / Opus / Vorbis / FLAC 几乎所有手机都有,AC3 / EAC3 / DTS / TrueHD 看机型)。 */
    fun audioOk(acodec: String): Boolean {
        val a = acodec.lowercase()
        if (a.startsWith("pcm_")) return true
        val m = audioMime[a] ?: return false
        return m in decoders
    }

    private val containerOkTokens = setOf("mov", "mp4", "m4a", "3gp", "3g2", "mj2", "matroska", "webm", "mpegts", "mpeg", "flv", "ogg", "wav", "mp3", "aac", "ac3", "flac", "amr")

    /** ExoPlayer 的解析器能认的容器(ffprobe 的 format_name 用逗号分隔多个别名,有一个认识就算)。空 = 不知道,当作行。 */
    fun containerOk(container: String): Boolean {
        if (container.isBlank()) return true
        return container.lowercase().split(',', ' ').any { it.trim() in containerOkTokens }
    }

    /**
     * 这个视频要不要走"兼容版本"(只转音频 / 换容器,视频流原样复制):
     * 视频能解(且编码能装进 MP4),但 容器认不出 或 默认音轨解不了。其它情况(视频本身解不了)只能整条转码,直放的由原有逻辑处理。
     */
    fun needsCompat(v: VideoInfo?): Boolean {
        v ?: return false
        if (v.vcodec.isBlank()) return false
        val vc = v.vcodec.lowercase()
        if (vc !in setOf("h264", "hevc", "av1", "vp9") || !videoOk(vc)) return false
        val audioBad = v.acodecs.isNotEmpty() && !audioOk(v.acodecs.first())
        return !containerOk(v.container) || audioBad
    }
}
