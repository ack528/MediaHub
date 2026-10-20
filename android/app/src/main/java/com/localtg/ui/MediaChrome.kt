package com.localtg.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localtg.ui.glass.mediaGlass
import com.localtg.ui.glass.liquidPress

/**
 * 查看器 / 播放器统一的玻璃按钮:圆形的深色玻璃(见 [mediaGlass]),白色图标。
 * 和 App 其它页面的液态玻璃同一种观感(半透明膜 + 边缘高光),只是不采样画面(视频画面采不到)。
 */
@Composable
fun GlassCircleButton(icon: ImageVector, desc: String, size: Dp = 44.dp, enabled: Boolean = true, tint: Color = Color.White, onClick: () -> Unit) {
    // 液态玻璃的按压动效:按下放大一点、跟着手指被"拉"过去、按压处亮起一团柔光,松手弹回(见 glass/GlassMotion.kt)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val h = remember(scope) { com.localtg.ui.glass.InteractiveHighlight(scope) }
    Box(
        Modifier.size(size).liquidPress(h).mediaGlass(CircleShape)
            .clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(size * 0.52f).alpha(if (enabled) 1f else 0.4f))
    }
}
