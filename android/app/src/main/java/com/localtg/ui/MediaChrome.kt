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

/**
 * 查看器 / 播放器统一的玻璃按钮:圆形的深色玻璃(见 [mediaGlass]),白色图标。
 * 和 App 其它页面的液态玻璃同一种观感(半透明膜 + 边缘高光),只是不采样画面(视频画面采不到)。
 */
@Composable
fun GlassCircleButton(icon: ImageVector, desc: String, size: Dp = 44.dp, enabled: Boolean = true, tint: Color = Color.White, onClick: () -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier.size(size).pressScale(src, 0.12f).mediaGlass(CircleShape)
            .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(size * 0.52f).alpha(if (enabled) 1f else 0.4f))
    }
}
