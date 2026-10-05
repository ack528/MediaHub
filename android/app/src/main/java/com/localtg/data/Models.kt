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

/** Android 能直接解码显示的图片扩展名(Android 17:含 HEIC / AVIF)。其余格式走服务端 render(M4)。 */
val NATIVE_IMAGE_EXT = setOf("jpg", "jpeg", "jpe", "jfif", "png", "webp", "bmp", "gif", "heic", "heif", "avif")
