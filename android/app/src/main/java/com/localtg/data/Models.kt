package com.localtg.data

import kotlinx.serialization.Serializable

@Serializable
data class ServerInfo(val name: String, val version: String, val apiVersion: Int)

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
