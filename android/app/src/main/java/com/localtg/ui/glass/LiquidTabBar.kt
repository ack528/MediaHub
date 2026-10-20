/*
 * 悬浮的液态玻璃底栏。结构和动效移植自 Kyant0/AndroidLiquidGlass 示例程序的 LiquidBottomTabs
 * (原作者 Kyant,Apache License 2.0)。改动:配色 / 尺寸换成本项目的 Telegram 新版风格,标签内容是"图标 + 文字",
 * 设备不支持采样(Android 12 以下 / 设置里关了玻璃)时退回到半透明胶囊 + 弹簧滑动的选中块。
 */
package com.localtg.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.localtg.ui.tg.LocalTg
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

private val LocalTabScale = staticCompositionLocalOf { { 1f } }
// 底栏和选中块都是两头全圆的胶囊(按参考截图)
private val Capsule = RoundedCornerShape(50)
private val PuckShape = RoundedCornerShape(50)

/**
 * 液态玻璃底栏。动效:
 *  - 选中块(puck)在标签之间弹簧滑动,滑动时按速度横向拉伸、纵向压扁;
 *  - 按住 / 拖动选中块:整条底栏轻微放大、块变成一颗透镜(折射 + 色散 + 高光 + 内阴影),标签图标放大,可以拖到别的标签松手切换;
 *  - 手指位置有一团跟手的柔光(按压高光);拖动时整条底栏朝拖动方向被"拉"过去一点,松手弹回。
 */
@Composable
fun LiquidTabBar(tabs: List<Pair<ImageVector, String>>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val backdrop = LocalGlassBackdrop.current
    if (backdrop == null || glassLevel() == GlassLevel.Translucent) {
        SimpleTabBar(tabs, selected, modifier, onSelect)
        return
    }
    val tg = LocalTg.current
    val accent = tg.accent
    // 白天主题:底栏膜偏灰、更实一点,浮在白色列表上也分得清(以前 38% 的白膜在白底上几乎看不见)
    val container = if (tg.dark) tg.bar.copy(alpha = 0.50f) else Color(0xFFF4F4F7).copy(alpha = 0.78f)
    val liquid = glassLevel() == GlassLevel.Liquid
    val onSelectNow by rememberUpdatedState(onSelect)
    val tabsBackdrop = rememberLayerBackdrop()
    val count = tabs.size

    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        // 按参考截图:底栏左右内边距 8dp,每个标签格子等宽;选中块 46dp 高、比格子宽 6dp(左右各探出 3dp)
        val tabWidth = with(density) { (constraints.maxWidth.toFloat() - 16f.dp.toPx()) / count }
        val puckW = with(density) { tabWidth.toDp() + 6.dp }
        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) { 4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
            }
        }
        val scope = rememberCoroutineScope()
        var currentIndex by remember { mutableIntStateOf(selected) }
        val damped = remember(scope) {
            DampedDragAnimation(
                animationScope = scope, initialValue = selected.toFloat(), valueRange = 0f..(count - 1).toFloat(),
                visibilityThreshold = 0.001f, initialScale = 1f, pressedScale = 1.12f, // 放大太多会伸出底栏、露出后面的内容
                onDragStarted = {},
                onDragStopped = {
                    val target = targetValue.fastRoundToInt().fastCoerceIn(0, count - 1)
                    currentIndex = target
                    animateToValue(target.toFloat())
                    scope.launch { offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
                },
                onDrag = { _, drag ->
                    updateValue((targetValue + drag.x / tabWidth).fastCoerceIn(0f, (count - 1).toFloat()))
                    scope.launch { offsetAnimation.snapTo(offsetAnimation.value + drag.x) }
                },
            )
        }
        LaunchedEffect(selected) { currentIndex = selected }
        LaunchedEffect(damped) {
            snapshotFlow { currentIndex }.drop(1).collectLatest { i ->
                damped.animateToValue(i.toFloat())
                onSelectNow(i)
            }
        }
        val highlight = remember(scope) {
            InteractiveHighlight(scope, position = { size, _ -> Offset((damped.value + 0.5f) * tabWidth + panelOffset, size.height / 2f) })
        }

        // 底栏本体:玻璃胶囊,按住选中块时轻微放大
        Row(
            Modifier.graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop, shape = { Capsule },
                    effects = {
                        vibrancy()
                        blur(6f.dp.toPx())
                        // 折射只在边缘一圈(14dp 宽),中间保持清楚;以前 24 / 24 把整条底栏下面的内容都拉成了色带
                        if (liquid) lens(14f.dp.toPx(), 18f.dp.toPx())
                    },
                    highlight = { Highlight(width = 0.8f.dp, alpha = 0.6f) },
                    shadow = { Shadow(radius = 22f.dp, color = Color.Black.copy(alpha = if (tg.dark) 0.30f else 0.16f)) },
                    layerBlock = {
                        val s = lerp(1f, 1f + 16f.dp.toPx() / size.width, damped.pressProgress)
                        scaleX = s; scaleY = s
                    },
                    onDrawSurface = { drawRect(container) },
                )
                .then(highlight.modifier)
                .height(56.dp).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { tabs.forEachIndexed { i, (icon, label) -> TabItem(icon, label, tg.name) { currentIndex = i } } }

        // 同一排标签再画一遍(强调色、不可见),录进 tabsBackdrop:透过选中块看到的是蓝色的图标和文字
        CompositionLocalProvider(LocalTabScale provides { lerp(1f, 1.08f, damped.pressProgress) }) {
            Row(
                Modifier.clearAndSetSemantics {}.alpha(0f).layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop, shape = { Capsule },
                        effects = {
                            val p = damped.pressProgress
                            vibrancy()
                            blur(8f.dp.toPx())
                            // 图标层不折射:透过选中块看到的图标和文字保持清楚、不被扭曲
                        },
                        highlight = { Highlight.Default.copy(alpha = damped.pressProgress) },
                        onDrawSurface = { drawRect(container) },
                    )
                    .then(highlight.modifier)
                    .height(48.dp).fillMaxWidth().padding(horizontal = 8.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accent)),
                verticalAlignment = Alignment.CenterVertically,
            ) { tabs.forEachIndexed { i, (icon, label) -> TabItem(icon, label, accent, bold = true) { currentIndex = i } } }
        }

        // 选中块:平时是一块浅蓝色胶囊;按住 / 拖动时变成透镜
        Box(
            Modifier.padding(horizontal = 8.dp)
                .graphicsLayer { translationX = damped.value * tabWidth + panelOffset - 3.dp.toPx() }
                .then(highlight.gestureModifier)
                .then(damped.modifier)
                .width(puckW).height(46.dp)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop), shape = { PuckShape },
                    effects = {
                        val p = damped.pressProgress
                        // 不开色散:选中块放大后边缘会采到底栏外面,色散把那一圈拉成黄 / 青色的彩带
                        if (liquid) lens(5f.dp.toPx() * p, 7f.dp.toPx() * p)
                    },
                    highlight = { Highlight.Default.copy(alpha = damped.pressProgress) },
                    shadow = { Shadow(alpha = damped.pressProgress) },
                    innerShadow = { InnerShadow(radius = 8f.dp * damped.pressProgress, alpha = damped.pressProgress) },
                    layerBlock = {
                        scaleX = damped.scaleX
                        scaleY = damped.scaleY
                        val v = damped.velocity / 10f
                        scaleX /= 1f - (v * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (v * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val p = damped.pressProgress
                        drawRect(accent.copy(alpha = if (tg.dark) 0.22f else 0.13f), alpha = 1f - p)
                        drawRect(Color.Black.copy(alpha = 0.03f * p))
                    },
                ),
        )
    }
}

@Composable
private fun RowScope.TabItem(icon: ImageVector, label: String, color: Color, bold: Boolean = false, onClick: () -> Unit) {
    val scale = LocalTabScale.current
    Column(
        Modifier.clip(Capsule).clickable(interactionSource = null, indication = null, role = Role.Tab, onClick = onClick)
            .fillMaxHeight().weight(1f)
            .graphicsLayer { val s = scale(); scaleX = s; scaleY = s },
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = color, modifier = Modifier.size(24.dp))
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold) // 截图:标签 12sp 粗体,图标 24dp
    }
}

/** 不能采样时的底栏:半透明胶囊 + 弹簧滑动的选中块(同样的布局和配色)。 */
@Composable
private fun SimpleTabBar(tabs: List<Pair<ImageVector, String>>, selected: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    val tg = LocalTg.current
    val pos by animateFloatAsState(selected.toFloat(), spring(0.7f, 400f), label = "tab")
    BoxWithConstraints(modifier.height(56.dp).clip(Capsule).background((if (tg.dark) tg.bar else Color.White).copy(alpha = 0.94f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        val w = maxWidth / tabs.size
        Box(
            Modifier.align(Alignment.CenterStart).graphicsLayer { translationX = pos * w.toPx() - 3.dp.toPx() }.size(w + 6.dp, 46.dp).clip(PuckShape)
                .background(tg.accent.copy(alpha = if (tg.dark) 0.22f else 0.13f)),
        )
        Row(Modifier.fillMaxWidth().height(48.dp)) {
            tabs.forEachIndexed { i, (icon, label) ->
                TabItem(icon, label, if (i == selected) tg.accent else tg.name, bold = i == selected) { onSelect(i) }
            }
        }
    }
}
