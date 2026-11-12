package com.localtg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 查看器 / 播放器的设计令牌:和软件主体(网页界面,HeroUI 深色主题 + android/web/src/index.css 的覆盖)同一套颜色、圆角和字号。
 * 查看器的背景永远是黑的,所以浮在上面的菜单、提示都用深色主题的值,不随系统浅色 / 深色变化。
 *
 *   网页令牌              这里
 *   --accent              Accent    #0485F7(以前原生用的是 Telegram 蓝 #298ACF)
 *   --overlay(深色)      Overlay   #303035
 *   --surface-secondary   Surface2  #37373C
 *   --segment(深色)      Segment   #4A4A50(分段选择的选中块)
 *   --muted(深色)        Muted     #A1A1AA
 *   --separator(深色)    Separator #3C3C41
 *   rounded-3xl / 2xl     卡片 24dp / 行 16dp;按钮、胶囊全圆
 *   text-base / sm / xs   16 / 14 / 12sp
 */
internal object Vx {
    val Accent = Color(0xFF0485F7)
    val Fg = Color(0xFFFAFAFA)
    val Muted = Color(0xFFA1A1AA)
    /** 媒体上方(渐变遮罩上)的次要文字:比 Muted 亮一点,压在画面上也看得清 */
    val OnMediaMuted = Color(0xCCFFFFFF)
    val Overlay = Color(0xF5303035)
    val Surface2 = Color(0xFF37373C)
    val Segment = Color(0xFF4A4A50)
    val Separator = Color(0xFF3C3C41)
    val Success = Color(0xFF17C964)
    val Warning = Color(0xFFF7B750)
    val Danger = Color(0xFFFF383C)
    /** 媒体上的半透明胶囊(网页缩略图上的时长、GIF 标记:bg-black/55) */
    val Glass = Color(0x8C000000)

    val CardShape = RoundedCornerShape(24.dp)
    val RowShape = RoundedCornerShape(16.dp)

    val Title = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
    val Body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val BodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val Small = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    /** 时间、计数:等宽数字(网页的 tabular-nums),数字变化时不左右抖 */
    val Num = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
}

/** 顶栏 / 控制栏上的图标按钮(网页 Button isIconOnly variant="ghost"):圆形,按下有浅色底,图标 22dp。 */
@Composable
internal fun VxIconButton(icon: ImageVector, desc: String, enabled: Boolean = true, tint: Color = Color.White, size: Dp = 44.dp, onClick: () -> Unit) {
    val hap = LocalHaptics.current
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.size(size).pressScale(src, 0.12f).clip(CircleShape)
            .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled) { hap.perform(Hap.Click); onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = if (enabled) tint else Color(0x55FFFFFF), modifier = Modifier.size(size * 0.5f)) }
}

/** 浮在画面中央的大按钮(播放 / 重播 / 解锁):半透明黑底的圆(网页时间线视频上的播放标记同款)。 */
@Composable
internal fun VxGlassButton(icon: ImageVector, desc: String, size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(
        modifier.size(size).pressScale(src, 0.1f).clip(CircleShape).background(Color(0x66000000)).hapticClickable(src, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = Color.White, modifier = Modifier.size(size * 0.42f)) }
}

/** 文字按钮(网页 Button):primary = 强调色实底,否则 = 次级灰底。都是全圆胶囊、14sp 中粗。 */
@Composable
internal fun VxButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(
        modifier.heightIn(min = 40.dp).pressScale(src, 0.06f).clip(CircleShape)
            .background(if (primary) Vx.Accent else Vx.Surface2).hapticClickable(src, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, style = Vx.BodyMedium, maxLines = 1) }
}

/** 媒体上的小胶囊(页码、倒计时等):半透明黑底、全圆、12sp 中粗等宽数字。 */
@Composable
internal fun VxGlassPill(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = Color.White, style = Vx.Small.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"), maxLines = 1,
        modifier = modifier.clip(CircleShape).background(Vx.Glass).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** 滑块(网页 Slider):填充强调色、白色圆滑块、深灰轨道。 */
@Composable
internal fun vxSliderColors(): SliderColors = SliderDefaults.colors(
    thumbColor = Color.White, activeTrackColor = Vx.Accent, inactiveTrackColor = Vx.Segment,
    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
)
