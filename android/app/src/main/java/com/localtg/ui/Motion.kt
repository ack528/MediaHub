package com.localtg.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn

/**
 * 界面动效:
 *  - 页面之间(文件夹列表 → 文件夹 → 设置 …)每个页面是一个 Activity,转场按 设置 → 外观 → 动画 → 界面动画 来:
 *    系统风格(默认,用系统自己的 Activity 转场和预测性返回)/ 整屏滑动 + 视差 / Material 共享轴 / 淡入淡出 / 关闭,见 [activityAnims]。
 *    (3.3.1 以前这个设置只作用在已经不用导航的查看器上,改了看不出任何变化。)
 *  - 缩略图 → 全屏查看器:点哪里从哪里展开,见 Hero.kt。
 *  - 登录 / 退出登录等不相关页面之间:淡出再淡入 + 轻微缩放(fade through)。
 */
object Motion {
    /** M3 强调减速(进入)、强调加速(退出)、标准缓动。 */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** AOSP 的 fast_out_extra_slow_in(= Material 3 的 Emphasized 缓动)。 */
    val FastOutExtraSlowIn = Easing { f ->
        if (f < 0.166666f) 0.4f * EmphasizedAccelerate.transform(f / 0.166666f)
        else 0.4f + 0.6f * EmphasizedDecelerate.transform((f - 0.166666f) / 0.833334f)
    }

    /** 系统风格(默认):系统自己的 Activity 转场。 */
    const val SLIDE = "slide"
    /** 整屏滑动 + 视差(Telegram / iOS 式)。 */
    const val PARALLAX = "parallax"
    const val AXIS = "axis"
    const val FADE = "fade"
    const val OFF = "off"

    /**
     * 这种风格的 Activity 转场动画资源:[打开时新页面, 打开时旧页面, 返回时下面的页面, 返回时离开的页面];0 = 没有动画。
     * 系统风格返回 null(不覆盖,用系统的,返回手势有系统的预测性返回预览)。
     */
    fun activityAnims(style: String): IntArray? = when (style) {
        PARALLAX -> intArrayOf(com.localtg.R.anim.parallax_open_enter, com.localtg.R.anim.parallax_open_exit, com.localtg.R.anim.parallax_close_enter, com.localtg.R.anim.parallax_close_exit)
        AXIS -> intArrayOf(com.localtg.R.anim.axis_open_enter, com.localtg.R.anim.axis_open_exit, com.localtg.R.anim.axis_close_enter, com.localtg.R.anim.axis_close_exit)
        FADE -> intArrayOf(com.localtg.R.anim.fade_enter, com.localtg.R.anim.fade_exit, com.localtg.R.anim.fade_enter, com.localtg.R.anim.fade_exit)
        OFF -> intArrayOf(0, 0, 0, 0)
        else -> null
    }
}

/** 点进文件夹:从点的那一行展开。[center] = 那一行的中心在屏幕高度上的比例(返回时朝它收起)。 */
class Expand(val options: android.os.Bundle, val center: Float)

const val EXTRA_EXPAND = "expandCenter"

/**
 * 打开文件夹(小红书 / B 站式):新页面从点的那一行开始,裁剪区域一路展开到全屏(系统的 clip reveal 转场,内容不变形)。
 * [r] 是那一行在 [view] 里的位置。「界面动画」选「关闭动画」时返回 null(普通打开)。
 */
fun expandFrom(view: android.view.View, r: androidx.compose.ui.geometry.Rect?, style: String): Expand? {
    if (r == null || style == Motion.OFF || r.width < 1f || r.height < 1f || view.height <= 0) return null
    val o = android.app.ActivityOptions.makeClipRevealAnimation(view, r.left.toInt(), r.top.toInt(), r.width.toInt(), r.height.toInt())
    return Expand(o.toBundle(), (r.center.y / view.height).coerceIn(0f, 1f))
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
