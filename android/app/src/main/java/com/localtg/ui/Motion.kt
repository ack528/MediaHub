package com.localtg.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.navigation.NavBackStackEntry
import com.localtg.ui.tg.LocalSettings

/**
 * 界面动效(参照 Material Motion 规范与 Telegram / iOS 的主流做法):
 *  - 层级前进 / 返回:新页面从右侧滑入,旧页面向左小幅位移(视差);返回时反向。预测性返回手势会驱动同一组动画。
 *  - 缩略图 → 查看器:共享元素(容器转换),缩略图直接放大成全屏,返回时缩回原位。
 *  - 登录 / 退出登录等不相关页面之间:淡出再淡入 + 轻微缩放(fade through)。
 *  - 可在 设置 → 外观 → 界面动画 切换:滑动 / Material 共享轴 / 淡入淡出 / 关闭。
 */
object Motion {
    /** M3 强调减速(进入)、强调加速(退出)、标准缓动。 */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /**
     * AOSP 的 fast_out_extra_slow_in(= Material 3 的 Emphasized 缓动):
     * M 0,0 C 0.05,0 0.133333,0.06 0.166666,0.4 C 0.208333,0.82 0.25,1 1,1。
     * 两段三次贝塞尔拼起来:前 1/6 时间先慢慢加速(不是一上来就最大速度),然后长长的减速尾巴 —— 这是系统 Activity 转场的缓动。
     */
    val FastOutExtraSlowIn = Easing { f ->
        if (f < 0.166666f) 0.4f * EmphasizedAccelerate.transform(f / 0.166666f)
        else 0.4f + 0.6f * EmphasizedDecelerate.transform((f - 0.166666f) / 0.833334f)
    }

    /** 系统风格(默认):和 Android 系统 Activity 转场同一套参数(见 navMotion)。 */
    const val SLIDE = "slide"
    /** 整屏滑动 + 视差(Telegram / iOS 式)。 */
    const val PARALLAX = "parallax"
    const val AXIS = "axis"
    const val FADE = "fade"
    const val OFF = "off"
}

typealias NavEnter = AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition
typealias NavExit = AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition

/** 预测性返回(手势)专用:参数是手势起始边(0 = 左边缘,1 = 右边缘)。 */
typealias NavPredEnter = AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> EnterTransition
typealias NavPredExit = AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> ExitTransition

class NavMotion(
    val enter: NavEnter, val exit: NavExit, val popEnter: NavEnter, val popExit: NavExit,
    val predEnter: NavPredEnter, val predExit: NavPredExit,
)

/**
 * 预测性返回的动画规格。要点(参考 Telegram 的 ActionBarLayout、Voyager 的预测性返回实现、Android 官方文档):
 *  1. 手势进度由系统按手指位置给出,导航库把它当作动画的"播放进度"去 seek —— 所以必须用**线性**缓动,
 *     页面才会 1:1 跟手(用减速曲线会让页面在手指刚动时就跳出一大截);松手后剩余部分按同样的规格播完。
 *  2. 层级页面(列表 ← 聊天 ← 设置):上层页面整屏跟手滑出,下层页面从 1/4 屏处视差滑回(与按钮返回的方向 / 节奏一致)。
 *  3. 查看器 → 聊天:不滑动,整页缩小 + 淡出,露出下面的缩略图,松手后由共享元素飞回原位。
 *  4. 所有页面都有不透明背景(见 NavScreen),被覆盖的页面不会透出来。
 */
private val Linear = androidx.compose.animation.core.LinearEasing

private fun AnimatedContentTransitionScope<NavBackStackEntry>.poppingViewer() =
    initialState.destination.route?.startsWith("viewer") == true

fun navMotion(style: String, density: Float = 3f): NavMotion = when (style) {
    Motion.OFF -> NavMotion(
        { EnterTransition.None }, { ExitTransition.None }, { EnterTransition.None }, { ExitTransition.None },
        { EnterTransition.None }, { ExitTransition.None },
    )
    Motion.FADE -> NavMotion(
        { fadeIn(tween(200)) }, { fadeOut(tween(150)) }, { fadeIn(tween(200)) }, { fadeOut(tween(150)) },
        { fadeIn(tween(200, easing = Linear)) }, { fadeOut(tween(200, easing = Linear)) },
    )
    Motion.AXIS -> { // Material 共享轴 X:位移约 1/10 屏宽 + 淡入淡出
        val d = 300
        NavMotion(
            { slideInHorizontally(tween(d, easing = Motion.Standard)) { it / 10 } + fadeIn(tween(210, 90, Motion.Standard)) },
            { slideOutHorizontally(tween(d, easing = Motion.Standard)) { -it / 10 } + fadeOut(tween(90, easing = Motion.Standard)) },
            { slideInHorizontally(tween(d, easing = Motion.Standard)) { -it / 10 } + fadeIn(tween(210, 90, Motion.Standard)) },
            { slideOutHorizontally(tween(d, easing = Motion.Standard)) { it / 10 } + fadeOut(tween(90, easing = Motion.Standard)) },
            { edge -> slideInHorizontally(tween(d, easing = Linear)) { if (edge == 1) it / 10 else -it / 10 } + fadeIn(tween(d, easing = Linear)) },
            { edge ->
                if (poppingViewer()) predictiveViewerOut()
                else slideOutHorizontally(tween(d, easing = Linear)) { if (edge == 1) -it / 10 else it / 10 } + fadeOut(tween(d, easing = Linear))
            },
        )
    }
    Motion.PARALLAX -> { // 整屏滑动 + 视差(Telegram / iOS 式):新页面整屏滑入,旧页面退后 1/4 屏
        val d = 360
        NavMotion(
            { slideInHorizontally(tween(d, easing = Motion.EmphasizedDecelerate)) { it } },
            { slideOutHorizontally(tween(d, easing = Motion.EmphasizedDecelerate)) { -it / 4 } },
            { slideInHorizontally(tween(d, easing = Motion.EmphasizedDecelerate)) { -it / 4 } },
            { slideOutHorizontally(tween(d, easing = Motion.EmphasizedDecelerate)) { it } },
            // 手势:线性、整屏跟手;从右边缘划(RTL / 右手柄)则向左滑出
            { edge -> if (poppingViewer()) EnterTransition.None else slideInHorizontally(tween(d, easing = Linear)) { if (edge == 1) it / 4 else -it / 4 } },
            { edge -> if (poppingViewer()) predictiveViewerOut() else slideOutHorizontally(tween(d, easing = Linear)) { if (edge == 1) -it else it } },
        )
    }
    else -> { // 系统风格(默认):和 Android 系统的 Activity 转场一致 —— Clash Meta / Shizuku 这类多 Activity 应用直接用的就是这套
        // AOSP activity_open_enter / activity_open_exit / activity_close_enter / activity_close_exit:
        //   前进:新页面从右侧 96dp 处滑到位 + 83ms 淡入(延迟 50ms);旧页面同时向左 96dp。
        //   返回:离开的页面向右 96dp + 83ms 淡出(延迟 35ms);回来的页面从左侧 96dp 滑回。
        //   时长 450ms,缓动 fast_out_extra_slow_in。距离只有 96dp(不是整屏),所以柔和、不生硬。
        val dur = 450
        val px = (96 * density).toInt()
        val e = Motion.FastOutExtraSlowIn
        NavMotion(
            { slideInHorizontally(tween(dur, easing = e)) { px } + fadeIn(tween(83, 50, Linear)) },
            { slideOutHorizontally(tween(dur, easing = e)) { -px } },
            { slideInHorizontally(tween(dur, easing = e)) { -px } },
            { slideOutHorizontally(tween(dur, easing = e)) { px } + fadeOut(tween(83, 35, Linear)) },
            // 手势:仿系统的"跨 Activity 预测性返回" —— 离开的页面缩到 90%、带圆角(见 NavScreen)、朝手指一侧偏移,最后淡出;
            // 下面的页面带一层变暗的遮罩,随进度变亮。全部用线性缓动,保证跟手。
            { edge -> if (poppingViewer()) EnterTransition.None else slideInHorizontally(tween(dur, easing = Linear)) { if (edge == 1) px / 2 else -px / 2 } },
            { edge ->
                if (poppingViewer()) predictiveViewerOut()
                else androidx.compose.animation.scaleOut(tween(dur, easing = Linear), targetScale = 0.9f) +
                    slideOutHorizontally(tween(dur, easing = Linear)) { (it * 0.05f * (if (edge == 1) -1 else 1)).toInt() } +
                    fadeOut(tween(dur / 3, dur * 2 / 3, Linear))
            },
        )
    }
}

/** 查看器的手势返回:整页缩小 + 淡出(线性,跟手),下面的聊天页原地不动,缩略图随之显露。 */
private fun predictiveViewerOut(): ExitTransition =
    androidx.compose.animation.scaleOut(tween(300, easing = Linear), targetScale = 0.85f) + fadeOut(tween(300, easing = Linear))

/** fade through:用于互不相关的页面(登录 ↔ 列表)。 */
fun fadeThroughIn(style: String): EnterTransition = when (style) {
    Motion.OFF -> EnterTransition.None
    Motion.FADE -> fadeIn(tween(200))
    else -> fadeIn(tween(210, 90, Motion.Standard)) + scaleIn(tween(300, easing = Motion.Standard), initialScale = 0.92f)
}

fun fadeThroughOut(style: String): ExitTransition = when (style) {
    Motion.OFF -> ExitTransition.None
    else -> fadeOut(tween(90, easing = Motion.Standard))
}

/**
 * 查看器自身的进出:只做淡入 / 淡出。缩略图 ↔ 全屏的放大缩小交给共享元素,
 * 页面本身不要再缩放(否则共享元素飞回去时,整页还在缩小,会留下一块半透明的矩形)。
 */
fun viewerIn(style: String): EnterTransition = if (style == Motion.OFF) EnterTransition.None else fadeIn(tween(200, easing = Motion.Standard))
fun viewerOut(style: String): ExitTransition = if (style == Motion.OFF) ExitTransition.None else fadeOut(tween(260, easing = Motion.Standard))

// ---------------------------------------------------------------- 共享元素

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransition = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** 缩略图 ↔ 查看器的共享元素(容器转换)。动画设为"关闭"或不在导航动画作用域内时不生效。 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.mediaShared(id: String, shape: Shape): Modifier {
    if (LocalSettings.current.motion == Motion.OFF) return this
    val st = LocalSharedTransition.current ?: return this
    val av = LocalNavScope.current ?: return this
    return with(st) {
        this@mediaShared.sharedBounds(
            sharedContentState = rememberSharedContentState(key = "media-$id"),
            animatedVisibilityScope = av,
            enter = fadeIn(tween(150)), exit = fadeOut(tween(150)),
            boundsTransform = { _, _ -> tween(380, easing = Motion.EmphasizedDecelerate) },
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Crop),
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}
