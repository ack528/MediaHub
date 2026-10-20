package com.localtg.ui.tg

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localtg.ui.glass.GlassHost
import com.localtg.ui.glass.GlassKind
import com.localtg.ui.glass.LocalBottomInset
import com.localtg.ui.glass.glass

/** 玻璃顶栏的高度(不含状态栏)。 */
val GlassBarHeight = 64.dp

/** 玻璃顶栏膜的不透明度:能看出下面的内容在滚动,又不影响标题的阅读(液态玻璃强度"适度")。 */
const val GlassBarAlpha = 0.70f

/**
 * 页面内容被盖住的高度:top = 状态栏 + 玻璃顶栏,bottom = 悬浮底栏 + 导航栏。
 * 列表把它加进 contentPadding:内容一直铺到屏幕顶和底(从玻璃下面透出来),第一项 / 最后一项又能完整露出来。
 */
val LocalBarInsets = compositionLocalOf { PaddingValues(0.dp) }

/**
 * 统一的页面骨架:内容铺满整个屏幕,顶栏是液态玻璃(膜色 = 顶栏色的 [GlassBarAlpha]),浮在内容上面。
 * [bar] 是顶栏里的内容(标题行、标签行…),高度由内容决定;[content] 从 [LocalBarInsets] 读要留出的上下高度。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TgGlassScaffold(
    background: Color = Color.Transparent,
    barTint: Color = LocalTg.current.bar.copy(alpha = GlassBarAlpha),
    bar: @Composable ColumnScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val tg = LocalTg.current
    val density = LocalDensity.current
    val status = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    var barPx by remember { mutableIntStateOf(0) }
    val top = if (barPx > 0) with(density) { barPx.toDp() } else status + 56.dp
    val bottom = LocalBottomInset.current
    val bd = com.localtg.ui.LocalBackdrop.current
    if (bd != null) androidx.compose.runtime.SideEffect { bd.barColor = barTint.copy(alpha = 1f) }
    GlassHost(
        background = background,
        content = { CompositionLocalProvider(LocalBarInsets provides PaddingValues(top = top, bottom = bottom)) { content() } },
        overlay = {
            val line = if (tg.dark) Color.Transparent else tg.divider.copy(alpha = 0.6f)
            Column(
                Modifier.fillMaxWidth()
                    .onSizeChanged { barPx = it.height; if (bd != null) bd.barHeight = it.height.toFloat() }
                    .glass(RoundedCornerShape(0.dp), GlassKind.Bar, tint = barTint)
                    .drawBehind { drawLine(line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), strokeWidth = 1f) }
                    .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility),
                content = bar,
            )
        },
    )
}

/**
 * Telegram 新版设置页的骨架:浅灰页面底,顶栏和页面同色的液态玻璃;有返回键时标题 22sp 粗体,没有(底栏里的「设置」标签)时 28sp 大标题。
 * [content] 拿到的 top = 状态栏 + 顶栏的高度,内容自己在顶部留出这么多(放在滚动容器里面,才能滚到顶栏下面)。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TgGlassPage(
    title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (top: Dp) -> Unit,
) {
    val tg = LocalTg.current
    val status = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    val top = status + GlassBarHeight
    GlassHost(
        background = tg.gray,
        content = { CompositionLocalProvider(LocalBarInsets provides PaddingValues(top = top, bottom = LocalBottomInset.current)) { content(top) } },
        overlay = {
            Row(
                Modifier.fillMaxWidth().height(top)
                    .glass(RoundedCornerShape(0.dp), GlassKind.Bar, tint = tg.gray.copy(alpha = GlassBarAlpha))
                    .padding(top = status, start = if (onBack != null) 10.dp else 22.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) BarIcon(TgIcons.Back, "返回", onBack) // 图标色 = barText,和标题同色
                Text(
                    title, color = tg.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, // 截图:大标题 24sp 粗体,有没有返回键都一样
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = if (onBack != null) 24.dp else 0.dp),
                )
                actions()
            }
        },
    )
}

/**
 * 列表的 contentPadding:上面让出玻璃顶栏,下面让出悬浮底栏(没有底栏时让出导航栏),再各加 [extra]。
 */
@Composable
fun barContentPadding(extra: Dp = 0.dp, horizontal: Dp = 0.dp): PaddingValues {
    val ins = LocalBarInsets.current
    val nav = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val b = ins.calculateBottomPadding()
    return PaddingValues(start = horizontal, end = horizontal, top = ins.calculateTopPadding() + extra, bottom = (if (b > 0.dp) b else nav) + extra)
}
