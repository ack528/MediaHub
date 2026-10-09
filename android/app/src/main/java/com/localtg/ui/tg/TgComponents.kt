package com.localtg.ui.tg

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.localtg.data.Api
import com.localtg.data.Item
import com.localtg.data.Last
import com.localtg.ui.canShowNatively

/** Telegram 风格的蓝色顶栏(含状态栏区域)。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TgBar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    // 用"忽略可见性"的状态栏高度:从沉浸式的查看器返回时,状态栏还没恢复,顶栏高度也不会跳动
    Column(
        modifier.fillMaxWidth().background(LocalTg.current.bar)
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility),
        content = content,
    )
}

@Composable
fun BarIcon(icon: ImageVector, desc: String?, onClick: () -> Unit) {
    val hap = com.localtg.ui.LocalHaptics.current
    IconButton(onClick = { hap?.perform(com.localtg.ui.Hap.Click); onClick() }) { Icon(icon, desc, tint = LocalTg.current.barText) }
}

private val avatarColors = listOf(0xFFE56555, 0xFFF28C48, 0xFF8E85EE, 0xFF76C84D, 0xFF5FBED5, 0xFF549CDD, 0xFFF2749A)

fun Last.toItem(dialogId: String) = Item(
    id = id, dialogId = dialogId, name = name, ext = name.substringAfterLast('.', "").lowercase(),
    type = type, w = w, h = h,
)

/**
 * 群头像:最近一条媒体(视频用封面,照片用原图)的方形缩略(小圆角);没有可显示的图片时,
 * 用 Telegram 的彩色底 + 首字母。
 */
@Composable
fun TgAvatar(title: String, key: String, size: Dp, cover: Item?, api: Api) {
    val color = Color(avatarColors[(key.hashCode() and 0x7fffffff) % avatarColors.size])
    val cfg = com.localtg.ui.tg.LocalSettings.current
    val shape = when (cfg.avatarShape) { "circle" -> CircleShape; "square" -> androidx.compose.ui.graphics.RectangleShape; else -> RoundedCornerShape(size * 0.18f) }
    Box(Modifier.size(size).clip(shape).background(color), contentAlignment = Alignment.Center) {
        Text(
            title.trim().firstOrNull()?.uppercase() ?: "#", color = Color.White,
            fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Medium,
        )
        if (cfg.avatarCover && cover != null && cover.canShowNatively()) {
            val url = if (cover.isVideo) api.posterUrl(cover.id) else api.imageUrl(cover, 480)
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).build(),
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size),
            )
        }
    }
}

/** 半透明圆角小胶囊(日期、时间、时长共用)。 */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, sizeSp: Int = 13, bold: Boolean = false) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(LocalTg.current.pill)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, color = Color.White, fontSize = sizeSp.sp, fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal)
    }
}
