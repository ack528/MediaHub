package com.localtg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.localtg.data.Api
import com.localtg.data.Item
import com.localtg.data.NATIVE_IMAGE_EXT
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFmt = DateTimeFormatter.ofPattern("yyyy/M/d")
private val dateTimeFmt = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm")

fun formatDate(rfc: String): String = runCatching {
    OffsetDateTime.parse(rfc).atZoneSameInstant(ZoneId.systemDefault()).format(dateFmt)
}.getOrDefault("")

fun formatDateTime(rfc: String): String = runCatching {
    OffsetDateTime.parse(rfc).atZoneSameInstant(ZoneId.systemDefault()).format(dateTimeFmt)
}.getOrDefault("")

fun formatDuration(ms: Long?): String {
    if (ms == null || ms <= 0) return ""
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

/** 图片能否由 Android 直接解码显示;否则需要服务端转换(M4)。 */
fun Item.canShowNatively(): Boolean = isVideo || type == "photo" || type == "gif" // 手机不能直接显示的格式由服务端转成 JPEG

/** 损坏 / 无法加载时的占位:警告图标 + 一句话说明(不再是一块空白)。 */
@Composable
fun BrokenTile(text: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    Box(modifier.background(Color(0xFF3A3F47)), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Icon(
                com.localtg.ui.tg.TgIcons.Warning, null, tint = Color(0xFFFFB74D),
                modifier = Modifier.size(if (compact) 22.dp else 34.dp),
            )
            Text(
                text, color = Color(0xCCFFFFFF), fontSize = if (compact) 10.sp else 12.sp,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp), maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** 文件损坏时占位上显示的简短文字;不损坏返回 null。 */
fun Item.brokenLabel(): String? = when {
    size == 0L -> "空文件"
    flags.corrupt -> "文件损坏"
    else -> null
}

/**
 * 网格/列表里的一格缩略图:视频用封面,图片直接用原图(Coil 按格子尺寸降采样解码)。
 * loadEnabled=false(快速滑动中)时只读本地缓存,不发起网络请求。
 */
@Composable
fun MediaThumb(item: Item, api: Api, modifier: Modifier = Modifier, loadEnabled: Boolean = true) {
    Box(modifier.background(Color(0x22888888))) {
        if (item.brokenLabel() != null) {
            BrokenTile(item.brokenLabel()!!, Modifier.fillMaxSize(), compact = true)
        } else if (!item.canShowNatively()) {
            Text(item.ext.uppercase(), Modifier.align(Alignment.Center), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            var failed by androidx.compose.runtime.remember(item.id) { androidx.compose.runtime.mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(loadEnabled) { if (loadEnabled) failed = false } // 快速滑动时被暂停的请求不算失败
            val url = if (item.isVideo) api.posterUrl(item) else api.imageUrl(item, com.localtg.ui.tg.LocalSettings.current.gridThumbWidth)
            val req = ImageRequest.Builder(LocalContext.current)
                .data(url)
                .networkCachePolicy(if (loadEnabled) CachePolicy.ENABLED else CachePolicy.DISABLED)
                .build()
            AsyncImage(
                model = req, contentDescription = item.name, contentScale = ContentScale.Crop,
                placeholder = ColorPainter(Color(0x22888888)), modifier = Modifier.fillMaxSize(),
                onError = { failed = true }, onSuccess = { failed = false },
            )
            if (failed && loadEnabled) BrokenTile("预览不可用", Modifier.fillMaxSize(), compact = true)
        }
        if (item.flags.truncated) { // 不完整的视频(下载中断):角上放个警告图标
            androidx.compose.material3.Icon(
                com.localtg.ui.tg.TgIcons.Warning, "文件不完整", tint = Color(0xFFFFB74D),
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp),
            )
        }
        if (item.isVideo) {
            Box(
                Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(4.dp))
                    .background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 1.dp),
            ) { Text(if (com.localtg.ui.tg.LocalSettings.current.showDuration) "▶ " + formatDuration(item.durationMs) else "▶", color = Color.White, fontSize = 11.sp) }
        }
    }
}
