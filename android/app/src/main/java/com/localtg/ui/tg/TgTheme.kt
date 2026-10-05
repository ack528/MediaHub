package com.localtg.ui.tg

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
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
 * Telegram Android 经典主题的配色(白天 / 深蓝夜间)。
 * 行高 70dp、头像直径 52dp、标题 17sp、消息 16sp 取自官方源码 DialogCell.java;
 * 颜色与气泡圆角为按 Telegram 默认主题的近似值,需要在真机上微调。
 */
@Immutable
data class TgPalette(
    val dark: Boolean,
    val bar: Color, val barText: Color, val barSub: Color, val barTabIdle: Color,
    val bg: Color, val name: Color, val message: Color, val date: Color, val divider: Color,
    val badge: Color, val badgeMuted: Color, val accent: Color, val attach: Color,
    val chatTop: Color, val chatBottom: Color, val bubbleIn: Color, val bubbleText: Color, val pill: Color,
)

val TgLight = TgPalette(
    dark = false,
    bar = Color(0xFF527DA3), barText = Color.White, barSub = Color(0xFFCDE3F8), barTabIdle = Color(0xB3FFFFFF),
    bg = Color.White, name = Color(0xFF222222), message = Color(0xFF8A8A8A), date = Color(0xFF999999), divider = Color(0xFFDBDBDB),
    badge = Color(0xFF4EC95E), badgeMuted = Color(0xFFC6C9CC), accent = Color(0xFF3390EC), attach = Color(0xFF3C7EB0),
    chatTop = Color(0xFFD8DDB0), chatBottom = Color(0xFF8AB488), bubbleIn = Color.White, bubbleText = Color(0xFF000000),
    pill = Color(0x66000000),
)

val TgDark = TgPalette(
    dark = true,
    bar = Color(0xFF212D3B), barText = Color.White, barSub = Color(0xFF8DA0B3), barTabIdle = Color(0x99FFFFFF),
    bg = Color(0xFF18222D), name = Color.White, message = Color(0xFF7F91A4), date = Color(0xFF6D7F8F), divider = Color(0xFF0F1721),
    badge = Color(0xFF4C8EDA), badgeMuted = Color(0xFF4A5A6B), accent = Color(0xFF6AB3F3), attach = Color(0xFF6AB3F3),
    chatTop = Color(0xFF0E1621), chatBottom = Color(0xFF0B121A), bubbleIn = Color(0xFF182533), bubbleText = Color.White,
    pill = Color(0x66000000),
)

val LocalTg = staticCompositionLocalOf { TgLight }

/** 当前应用设置(界面据此调整行为)。 */
val LocalSettings = staticCompositionLocalOf { com.localtg.data.AppSettings() }

@Composable
fun TgTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val p = if (dark) TgDark else TgLight
    val scheme = if (p.dark) {
        darkColorScheme(primary = p.accent, background = p.bg, surface = p.bg, onSurface = p.name, onBackground = p.name,
            surfaceVariant = p.bubbleIn, onSurfaceVariant = p.message, outline = p.divider)
    } else {
        lightColorScheme(primary = p.accent, background = p.bg, surface = p.bg, onSurface = p.name, onBackground = p.name,
            surfaceVariant = Color(0xFFF1F1F1), onSurfaceVariant = p.message, outline = p.divider)
    }
    CompositionLocalProvider(LocalTg provides p) {
        MaterialTheme(colorScheme = scheme) {
            Surface(Modifier.fillMaxSize(), color = p.bg, content = content)
        }
    }
}
