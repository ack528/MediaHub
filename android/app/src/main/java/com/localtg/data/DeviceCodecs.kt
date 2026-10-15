package com.localtg.data

import android.media.MediaCodecList
import android.os.Build

/**
 * 本机的视频解码能力:决定服务端转码输出 H.265 时手机能不能顺畅硬解(软解 H.265 会很吃力)。
 */
object DeviceCodecs {
    /** 手机有 H.265(HEVC)硬件解码器。 */
    val hevcHardware: Boolean by lazy { hasHardwareDecoder("video/hevc") }

    private fun hasHardwareDecoder(mime: String): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
            !ci.isEncoder && mime in ci.supportedTypes && !ci.name.startsWith("OMX.google") && !ci.name.startsWith("c2.android") &&
                (Build.VERSION.SDK_INT < 29 || ci.isHardwareAccelerated)
        }
    }.getOrDefault(false)

    /**
     * 把设置里的转码编码("auto" / "h264" / "h265")落实成这次请求要用的编码:
     * auto = 服务端能输出 H.265(有 QSV 硬编)且手机能硬解 H.265 → h265(同码率画质更好,同画质更省流量);否则 h264。
     * 手动指定 h265 但服务端不支持时退回 h264(服务端也会再检查一次)。
     */
    fun resolveCodec(pref: String, serverCodecs: List<String>): String {
        val serverHas265 = "h265" in serverCodecs
        return when (pref) {
            "h265" -> if (serverHas265) "h265" else "h264"
            "h264" -> "h264"
            else -> if (serverHas265 && hevcHardware) "h265" else "h264"
        }
    }
}

/** 转码画质档位:只提供 6 Mbps 及以下的(再高的码率直接播原文件更好);低码率档位给得更密,网络差时有更多选择。 */
object QualityTiers {
    const val MAX = 6_000_000
    val ALL = listOf(500_000, 800_000, 1_000_000, 1_500_000, 2_000_000, 3_000_000, 4_000_000, 5_000_000, 6_000_000)

    /** 服务端有码率上限就取更小的那个(老服务端是 0 = 不限,但本应用仍然只用到 6M)。 */
    fun cap(serverMax: Int): Int = if (serverMax in 1 until MAX) serverMax else MAX

    fun tiers(serverMax: Int): List<Int> = ALL.filter { it <= cap(serverMax) }

    fun label(bps: Int): String = if (bps < 1_000_000) "${bps / 1000}K" else (bps / 1_000_000f).let { if (it % 1f == 0f) "${it.toInt()}M" else "%.1fM".format(it) }
}
