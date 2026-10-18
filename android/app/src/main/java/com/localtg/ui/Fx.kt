package com.localtg.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localtg.ui.tg.LocalSettings
import kotlinx.coroutines.delay

/**
 * 细节动效(设置 → 外观 → 动画 → 细节动效,默认开):弹簧按压回弹、列表入场、标签指示条跟手滑动、登录页入场。
 *
 * 思路取自 onetake(github.com/feitangyuan/onetake,做"一镜到底"动效片的 Agent 技能)的几条规则,只借想法、不用它的代码:
 *  1. 入场用弹簧,不用固定时长的缓动:带一点点过冲(阻尼比 0.6 左右)才像"落下来",而不是"淡进来"。
 *     它量过的参数:popIn ζ0.62 ω18、wordRise ζ0.6 ω22、按下 / 点击回弹 ζ0.5 ω28。Compose 的 spring 质量固定为 1,刚度 = ω²。
 *  2. 透明度比位移"跑得快"(opacity 乘 1.5 再截到 1),这样入场读起来是弹出,不是渐显。
 *  3. 两个状态之间要有东西"活下来":标签下划线不是在两个标签上各画一条,而是同一条在标签之间滑动,并且跟着手指的横滑走。
 *  4. 有动也要有停:只在第一屏入场、只对被按住的东西回弹,滚动、连续刷新时不动,动效才显得有分量。
 * 「界面动画」选「关闭动画」时这些也全部关闭。
 */
object Springs {
    /** 入场:ζ0.62、ω≈20(刚度 400),落定约 0.5 秒。 */
    fun <T> rise(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.62f, stiffness = 400f)

    /** 悬浮按钮 / 小控件弹出:稍弹、稍快。 */
    fun <T> pop(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.55f, stiffness = 520f)

    /** 按下:快、几乎不过冲,手指一碰就缩。 */
    fun <T> press(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 900f)

    /** 松手:ζ0.5 的回弹,放大一点点再落回 1,像按键弹起。 */
    fun <T> release(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.5f, stiffness = 600f)
}

/** 细节动效是否生效:设置里开着,并且「界面动画」没选关闭。 */
@Composable
fun fineMotionOn(): Boolean {
    val s = LocalSettings.current
    return s.fineMotion && s.motion != Motion.OFF
}

/**
 * 按住时缩小、松手时弹回(按压回弹)。放在 clickable / 背景 / 裁剪**之前**,它们才会跟着一起缩放。
 * [source] 要和 clickable 用同一个 InteractionSource。长按、拖动开始(按压被取消)同样会弹回。
 */
@Composable
fun Modifier.pressScale(source: InteractionSource, depth: Float = 0.06f): Modifier {
    if (!fineMotionOn()) return this
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 1f - depth else 1f, if (pressed) Springs.press() else Springs.release(), label = "press")
    return this.graphicsLayer { scaleX = s; scaleY = s }
}

/** 带震动反馈、和 [pressScale] 共用 InteractionSource 的点击。 */
@Composable
fun Modifier.hapticClickable(source: MutableInteractionSource, kind: Hap = Hap.Click, enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val hap = LocalHaptics.current
    return this.clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled) { hap.perform(kind); onClick() }
}

/**
 * 入场:弹簧上浮 + 轻微放大,透明度领先位移。[order] 决定错开多久(每个 45ms,最多 10 个),
 * [enabled] 只在这个组件**第一次**组合时读取一次 —— 滚动时才进入屏幕的行不会各自再入场一遍;动画播完后修饰符自己拿掉,不留图层。
 */
@Composable
fun Modifier.springEnter(order: Int = 0, enabled: Boolean = true, rise: Dp = 22.dp): Modifier {
    val fine = fineMotionOn()
    val run = remember { enabled && fine }
    val k = remember { Animatable(if (run) 0f else 1f) }
    var done by remember { mutableStateOf(!run) }
    LaunchedEffect(Unit) {
        if (!run) return@LaunchedEffect
        delay(order.coerceIn(0, 10) * 45L)
        k.animateTo(1f, Springs.rise())
        done = true
    }
    if (done) return this
    val risePx = with(LocalDensity.current) { rise.toPx() }
    return this.graphicsLayer {
        val v = k.value
        alpha = (v * 1.5f).coerceIn(0f, 1f)
        translationY = (1f - v) * risePx
        val sc = 0.96f + 0.04f * v
        scaleX = sc; scaleY = sc
    }
}

/**
 * 标签指示条的位置:[p] 是分页器的连续位置(当前页 + 偏移比例),[pos] 是每个标签的 (左边, 宽度)。
 * 在相邻两个标签之间线性插值 —— 手指拖多少,指示条走多少,松手后由分页器自己的动画带到位。
 * 标签还没量出来(pos 里缺)时返回 null。
 */
fun tabIndicator(p: Float, pos: Map<Int, Pair<Float, Float>>, count: Int): Pair<Float, Float>? {
    if (count <= 0) return null
    val q = p.coerceIn(0f, (count - 1).toFloat())
    val i = q.toInt().coerceIn(0, count - 1)
    val j = (i + 1).coerceAtMost(count - 1)
    val f = q - i
    val a = pos[i] ?: return null
    val b = pos[j] ?: return a
    return (a.first + (b.first - a.first) * f) to (a.second + (b.second - a.second) * f)
}
