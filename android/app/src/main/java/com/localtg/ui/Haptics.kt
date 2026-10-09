package com.localtg.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** 震动反馈的种类:按"这个操作的分量"选,不要所有点击都用同一种。 */
enum class Hap {
    /** 最轻:翻页 / 切换一项 / 步进(连续出现也不烦) */
    Tick,
    /** 普通点击:按钮、标签页、开关、打开文件 */
    Click,
    /** 长按被识别 / 进入某种模式(倍速、跳转) */
    Long,
    /** 操作完成:保存成功、刷新完成、重新生成完毕 */
    Confirm,
    /** 被拒绝 / 到头了(已经是第一个 / 最后一个、失败) */
    Reject,
}

/**
 * 应用内的震动反馈(设置 → 外观 → 交互 → 震动反馈:关闭 / 轻 / 标准)。
 * 用 Vibrator 的预定义效果(Android 10+,马达调校过,比自己拼时长的手感好),低版本退回短促的单次振动。
 * 不走 View.performHapticFeedback:它会受系统「触感反馈」总开关限制,关了系统开关应用里就完全没有震动;
 * 这里由应用自己的开关决定。level 在每次触发时现读,设置改了马上生效。
 */
class Haptics(context: Context, private val level: () -> Int) {
    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }.getOrNull()?.takeIf { it.hasVibrator() }

    private var last = 0L

    fun perform(kind: Hap) {
        val lv = level()
        if (lv <= 0) return
        val v = vibrator ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - last < 35) return // 同一瞬间的多个事件(例如点击 + 翻页)只震一次
        last = now
        runCatching { v.vibrate(effect(kind, lv)) }
    }

    private fun effect(kind: Hap, lv: Int): VibrationEffect {
        val strong = lv >= 2
        if (Build.VERSION.SDK_INT >= 29) {
            val id = when (kind) {
                Hap.Tick -> VibrationEffect.EFFECT_TICK
                Hap.Click -> if (strong) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK
                Hap.Long -> if (strong) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_CLICK
                Hap.Confirm -> VibrationEffect.EFFECT_DOUBLE_CLICK
                Hap.Reject -> if (strong) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_CLICK
            }
            return VibrationEffect.createPredefined(id)
        }
        val (ms, amp) = when (kind) {
            Hap.Tick -> 8L to 60
            Hap.Click -> (if (strong) 14L else 9L) to (if (strong) 140 else 80)
            Hap.Long -> (if (strong) 28L else 18L) to (if (strong) 220 else 140)
            Hap.Confirm -> 30L to 160
            Hap.Reject -> (if (strong) 40L else 25L) to (if (strong) 255 else 180)
        }
        return VibrationEffect.createOneShot(ms, amp)
    }
}

/** 页面里用:`val hap = LocalHaptics.current` → `hap.perform(Hap.Click)`。没有提供时(预览)什么都不做。 */
val LocalHaptics = staticCompositionLocalOf<Haptics?> { null }

fun Haptics?.perform(kind: Hap) { this?.perform(kind) }

/** 点击时先震一下再执行:`Modifier.hapticClickable { ... }`。 */
@Composable
fun Modifier.hapticClickable(kind: Hap = Hap.Click, onClick: () -> Unit): Modifier {
    val hap = LocalHaptics.current
    return this.clickable { hap.perform(kind); onClick() }
}
