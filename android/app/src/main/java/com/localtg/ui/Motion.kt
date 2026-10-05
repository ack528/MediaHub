package com.localtg.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
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

    const val SLIDE = "slide"
    const val AXIS = "axis"
    const val FADE = "fade"
    const val OFF = "off"
}

typealias NavEnter = AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition
typealias NavExit = AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition

class NavMotion(val enter: NavEnter, val exit: NavExit, val popEnter: NavEnter, val popExit: NavExit)

fun navMotion(style: String): NavMotion = when (style) {
    Motion.OFF -> NavMotion({ EnterTransition.None }, { ExitTransition.None }, { EnterTransition.None }, { ExitTransition.None })
    Motion.FADE -> NavMotion(
        { fadeIn(tween(200)) }, { fadeOut(tween(150)) }, { fadeIn(tween(200)) }, { fadeOut(tween(150)) },
    )
    Motion.AXIS -> { // Material 共享轴 X:位移约 1/10 屏宽 + 淡入淡出
        val d = 300
        NavMotion(
            { slideInHorizontally(tween(d, easing = Motion.Standard)) { it / 10 } + fadeIn(tween(210, 90, Motion.Standard)) },
            { slideOutHorizontally(tween(d, easing = Motion.Standard)) { -it / 10 } + fadeOut(tween(90, easing = Motion.Standard)) },
            { slideInHorizontally(tween(d, easing = Motion.Standard)) { -it / 10 } + fadeIn(tween(210, 90, Motion.Standard)) },
            { slideOutHorizontally(tween(d, easing = Motion.Standard)) { it / 10 } + fadeOut(tween(90, easing = Motion.Standard)) },
        )
    }
    else -> { // 滑动 + 视差(默认):新页面整屏滑入,旧页面退后 1/4 屏
        val d = 360
        NavMotion(
            { slideInHorizontally(tween(d, easing = Motion.EmphasizedDecelerate)) { it } },
            { slideOutHorizontally(tween(d, easing = Motion.EmphasizedDecelerate)) { -it / 4 } },
            { slideInHorizontally(tween(d, easing = Motion.EmphasizedDecelerate)) { -it / 4 } },
            { slideOutHorizontally(tween(d, easing = Motion.EmphasizedDecelerate)) { it } },
        )
    }
}

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
