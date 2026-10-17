package com.localtg.data

import kotlinx.serialization.Serializable

@Serializable
data class ServerInfo(val name: String, val version: String, val apiVersion: Int, val transcode: Boolean = false, val caps: ServerCaps = ServerCaps())

/** 服务器在各个网络上的地址(登录后才能取):内网 IPv4、公网 IPv6、内网穿透的外网地址。 */
@Serializable
data class ServerAddrs(val lan: List<String> = emptyList(), val ipv6: List<String> = emptyList(), val public: String = "", val tls: Boolean = false)

/** 服务端能力(/server/info 的 caps):老服务端没有这项,全部取默认值(= 不支持)。 */
@Serializable
data class ServerCaps(
    val codecs: List<String> = listOf("h264"),    // 转码能输出的编码
    val qsvH264: Boolean = false,
    val qsvH265: Boolean = false,
    val maxTranscodeBitrate: Int = 0,             // 0 = 老服务端(不限制)
    val probing: Boolean = false,                 // 服务端还在启动时的硬件探测
    val speedtest: Boolean = false,
    val playerPrefs: Boolean = false,
    val hlsPrepare: Boolean = false,
    val thumbWebp: Boolean = false,
    val optimize: Boolean = false,
)

/** 播放器记住的转码偏好(服务端按用户保存)。bitrate = 0 或 direct = true 表示原画直接播放。 */
@Serializable
data class PlayerPref(val bitrate: Int = 0, val codec: String = "h264", val direct: Boolean = false, val savedAt: Long = 0)

@Serializable
data class LoginResp(val token: String, val expiresAt: String = "")

@Serializable
data class Counts(val photo: Int = 0, val video: Int = 0, val gif: Int = 0, val audio: Int = 0, val file: Int = 0)

@Serializable
data class Last(
    val id: String,
    val type: String,
    val name: String,
    val takenAt: String = "",
    val w: Int? = null,
    val h: Int? = null,
)

@Serializable
data class DState(val pinned: Boolean = false, val archived: Boolean = false, val unread: Int = 0)

@Serializable
data class Dialog(
    val id: String,
    val parentId: String? = null,
    val rootId: String,
    val title: String,
    val pathDisplay: String,
    val counts: Counts = Counts(),
    val recursiveCount: Int = 0,
    val last: Last? = null,
    val cover: List<String> = emptyList(),
    val topicsCount: Int = 0,
    val state: DState = DState(),
    val version: String = "",
) {
    /** 盘符标签:pathDisplay 形如 "D:\旅行\2023",取第一个反斜杠之前。 */
    val rootLabel: String get() = pathDisplay.substringBefore('\\')
    val mediaCount: Int get() = counts.photo + counts.video + counts.gif
}

@Serializable
data class DialogsResp(val dialogs: List<Dialog>, val nextCursor: String? = null)

@Serializable
data class VideoInfo(
    val container: String = "",
    val vcodec: String = "",
    val profile: String = "",
    val bitrateKbps: Long = 0,
    val hdr: String = "",
    val acodecs: List<String> = emptyList(),
    val subs: Int = 0,
)

@Serializable
data class ItemFlags(val animated: Boolean = false, val corrupt: Boolean = false, val truncated: Boolean = false)

@Serializable
data class Item(
    val id: String,
    val dialogId: String,
    val name: String,
    val ext: String,
    val type: String,
    val mime: String = "",
    val size: Long = 0,
    val w: Int? = null,
    val h: Int? = null,
    val rotation: Int = 0,
    val takenAt: String = "",
    val modifiedAt: String = "",
    val createdAt: String = "",
    val durationMs: Long? = null,
    val thumbhash: String? = null,
    val v: String = "",
    val video: VideoInfo? = null,
    /** 服务端检查出的文件状态:corrupt 无法解析 / 空文件;truncated 能解析但不完整(下载中断) */
    val flags: ItemFlags = ItemFlags(),
    /** 文件有问题时的中文说明(服务端给出) */
    val problem: String? = null,
) {
    val isVideo: Boolean get() = type == "video"
    val aspect: Float? get() = if (w != null && h != null && h > 0) w.toFloat() / h else null
}

@Serializable
data class HistoryResp(
    val items: List<Item>,
    val total: Int = 0,
    val firstRank: Int = 0,
    val nextCursor: String? = null,
    val prevCursor: String? = null,
)

/** Android 能可靠地直接解码显示的图片扩展名。 */
val NATIVE_IMAGE_EXT = setOf("jpg", "jpeg", "jpe", "jfif", "png", "webp", "bmp", "gif")

/** HEIC / HEIF / AVIF:系统能解码,但方向、色彩、个别文件会出问题,默认也交给服务端转换(设置里可改成手机直接解码)。 */
val HEIC_EXT = setOf("heic", "heif", "hif", "avif")

/** 这些格式是否需要服务端转成 JPEG 再显示(RAW、JXL、TIFF 等一律需要;HEIC 类看设置)。 */
fun needsServerRender(ext: String, renderHeic: Boolean): Boolean {
    val e = ext.lowercase()
    return when {
        e in NATIVE_IMAGE_EXT -> false
        e in HEIC_EXT -> renderHeic
        else -> true
    }
}
