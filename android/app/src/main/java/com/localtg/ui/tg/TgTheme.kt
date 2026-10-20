package com.localtg.ui.tg

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Telegram Android 当前默认主题的配色:白天 = ThemeColors.java 里的默认值(白色顶栏、深色标题 / 图标),
 * 夜间 = 官方 darkblue.attheme(Dark Blue)。以前用的是 2013 年那套蓝色顶栏的近似色。
 * 尺寸取自官方源码:DialogCell 行高 70dp / 标题 17sp / 消息 16sp;TextSettingsCell 行高 50dp / 16sp;HeaderCell 14sp;
 * TextInfoPrivacyCell 14sp;ShadowSectionCell 分组间隔 12dp(见 TgCells.kt)。
 */
@Immutable
data class TgPalette(
    val dark: Boolean,
    val bar: Color, val barText: Color, val barSub: Color, val barTabIdle: Color,
    val bg: Color, val name: Color, val message: Color, val date: Color, val divider: Color,
    val badge: Color, val badgeMuted: Color, val accent: Color, val attach: Color,
    val chatTop: Color, val chatBottom: Color, val bubbleIn: Color, val bubbleText: Color, val pill: Color,
    /** 语义色:危险 / 警告 / 良好 / 中性标签;弹窗 / 菜单的容器色 */
    val danger: Color = Color(0xFFE53935), val warn: Color = Color(0xFFF59E0B), val ok: Color = Color(0xFF4EC95E),
    val neutral: Color = Color(0xFF9AA5B1), val sheet: Color = Color.White,
    /** 设置类页面的灰色底(windowBackgroundGray)、顶栏里选中的标签 / 下划线的颜色、小节标题色、状态栏图标是否用深色(白色顶栏时)、开关两种状态的轨道色 */
    val gray: Color = Color(0xFFF1F1F3), val barActive: Color = Color(0xFF298ACF), val header: Color = Color(0xFF298ACF),
    val barIconsDark: Boolean = false, val switchOff: Color = Color(0xFFA6ADB3), val switchOn: Color = Color(0xFF229AF0),
)

val TgLight = TgPalette(
    dark = false,
    bar = Color.White, barText = Color(0xFF1A1D21), barSub = Color(0xFF79817E), barTabIdle = Color(0xFF777C7F),
    bg = Color.White, name = Color(0xFF1A1D21), message = Color(0xFF75787A), date = Color(0xFF848688), divider = Color(0xFFD9D9D9),
    badge = Color(0xFF229AF0), badgeMuted = Color(0xFFBEC3C7), accent = Color(0xFF298ACF), attach = Color(0xFF3C7EB0),
    chatTop = Color(0xFFD8DDB0), chatBottom = Color(0xFF8AB488), bubbleIn = Color.White, bubbleText = Color(0xFF000000),
    pill = Color(0x66000000), sheet = Color.White,
    danger = Color(0xFFCC4747), ok = Color(0xFF37A818),
    gray = Color(0xFFEFEFF4), barActive = Color(0xFF298ACF), header = Color(0xFF298ACF), barIconsDark = true,
    switchOff = Color(0xFFA6ADB3), switchOn = Color(0xFF229AF0),
)

val TgDark = TgPalette(
    dark = true,
    bar = Color(0xFF242D39), barText = Color.White, barSub = Color(0xFF83919E), barTabIdle = Color(0xFF8B97A2),
    bg = Color(0xFF1D2733), name = Color.White, message = Color(0xFF7D8B99), date = Color(0xFF737F8B), divider = Color(0x95000000),
    badge = Color(0xFF64B5EF), badgeMuted = Color(0xFF3E5263), accent = Color(0xFF64B5EF), attach = Color(0xFF6AB3F3),
    chatTop = Color(0xFF0E1621), chatBottom = Color(0xFF0B121A), bubbleIn = Color(0xFF232E3B), bubbleText = Color.White,
    pill = Color(0x66000000), danger = Color(0xFFEE686F), sheet = Color(0xFF212D3B),
    gray = Color(0xFF151E27), barActive = Color(0xFF6ABFFF), header = Color(0xFF79C4FC), barIconsDark = false,
    switchOff = Color(0xFF5D7484), switchOn = Color(0xFF61A3D7),
)

/** 全应用统一的字号:弹窗标题 18sp、小节标题 16sp、正文 16 / 14sp(以前各页各写各的,弹窗标题用的是 Material 默认的 24sp)。 */
private val TgTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
        titleMedium = t.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
        titleSmall = t.titleSmall.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        headlineSmall = t.headlineSmall.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    )
}

/** 统一圆角(Telegram 新版):下拉菜单 14dp、卡片 / 输入框 12–18dp、弹窗 22dp。 */
private val TgShapes = Shapes(
    extraSmall = RoundedCornerShape(14.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(18.dp), extraLarge = RoundedCornerShape(22.dp),
)

val LocalTg = staticCompositionLocalOf { TgLight }

/** 当前应用设置(界面据此调整行为)。 */
val LocalSettings = staticCompositionLocalOf { com.localtg.data.AppSettings() }

@Composable
fun TgTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val p = if (dark) TgDark else TgLight
    val scheme = if (p.dark) {
        darkColorScheme(primary = p.accent, onPrimary = Color.White, background = p.bg, surface = p.bg, onSurface = p.name, onBackground = p.name,
            surfaceVariant = p.bubbleIn, onSurfaceVariant = p.message, outline = p.divider, error = p.danger,
            surfaceContainer = p.sheet, surfaceContainerHigh = p.sheet, surfaceContainerHighest = p.sheet)
    } else {
        lightColorScheme(primary = p.accent, onPrimary = Color.White, background = p.bg, surface = p.bg, onSurface = p.name, onBackground = p.name,
            surfaceVariant = p.gray, onSurfaceVariant = p.message, outline = p.divider, error = p.danger,
            surfaceContainer = p.sheet, surfaceContainerHigh = p.sheet, surfaceContainerHighest = p.sheet)
    }
    BarIconsEffect(p.barIconsDark) // 白色顶栏(白天)状态栏 / 导航栏图标用深色,深蓝顶栏(夜间)用浅色
    CompositionLocalProvider(LocalTg provides p) {
        MaterialTheme(colorScheme = scheme, typography = TgTypography, shapes = TgShapes) {
            Surface(Modifier.fillMaxSize(), color = p.bg, content = content)
        }
    }
}

/** View 所在的 Activity 的窗口(Compose 的 LocalView.context 可能被 ContextWrapper 包着)。 */
fun android.view.View.hostWindow(): android.view.Window? {
    var c: android.content.Context? = context
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c.window
        c = c.baseContext
    }
    return null
}

private fun setBarIcons(view: android.view.View, dark: Boolean) {
    val w = view.hostWindow() ?: return
    val ctl = androidx.core.view.WindowCompat.getInsetsController(w, view)
    ctl.isAppearanceLightStatusBars = dark
    ctl.isAppearanceLightNavigationBars = dark
}

/**
 * 状态栏 / 导航栏图标颜色:dark = 用深色图标(背景是浅色时)。主题里按顶栏颜色设一次;查看器是黑底,打开期间改成浅色图标,
 * 离开(这个 Composable 退出组合)时恢复成主题的设置。
 */
@Composable
fun BarIconsEffect(dark: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect { if (!view.isInEditMode) setBarIcons(view, dark) }
    val themeDark = LocalTg.current.barIconsDark
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (!view.isInEditMode) setBarIcons(view, themeDark) } }
}
