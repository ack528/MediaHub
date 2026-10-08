package com.localtg.render

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.View
import com.localtg.AppLog

/**
 * 强制保持屏幕高刷新率(设置 → 外观 → 屏幕):整个应用在前台时,让系统把屏幕固定在 120 Hz 及以上。
 *
 * 做法(缺一不可,各家系统的"智能刷新率"会各自降频):
 *  1. 窗口的 preferredDisplayModeId:同分辨率下刷新率 ≥ 120 的显示模式里最高的那个 —— 比 preferredRefreshRate 更"硬",系统更不容易自己降下去;
 *  2. 窗口的 preferredRefreshRate 同步设成这个模式的刷新率;
 *  3. Android 15+:根 View 的 requestedFrameRate 设成"高帧率类别",告诉系统这个界面需要高刷(VRR 屏幕不会因为画面静止而降到 60 / 30)。
 * 屏幕没有 ≥ 120 Hz 的模式(60 / 90 Hz 的屏幕)就什么都不做。关掉开关时恢复由系统决定。
 * 注意:系统的省电模式、过热降频、用户在系统设置里手动锁了刷新率时,系统仍然可以覆盖应用的请求,这是应用管不了的。
 */
object HighRefresh {
    const val MIN_HZ = 119.5f

    /** 当前分辨率下刷新率 ≥ 120 Hz 的显示模式里最高的;没有返回 null。 */
    fun targetMode(display: Display?): Display.Mode? = runCatching {
        val cur = display!!.mode
        display.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight && it.refreshRate >= MIN_HZ }
            .maxByOrNull { it.refreshRate }
    }.getOrNull()

    /** 把窗口的刷新率请求设成"≥120 Hz 里最高的模式"(on = true),或恢复系统决定(on = false)。 */
    fun apply(activity: Activity, on: Boolean) {
        val w = activity.window ?: return
        val display = if (Build.VERSION.SDK_INT >= 30) activity.display else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay
        val mode = if (on) targetMode(display) else null
        val lp = w.attributes
        val wantId = mode?.modeId ?: 0
        val wantHz = mode?.refreshRate ?: 0f
        if (lp.preferredDisplayModeId != wantId || lp.preferredRefreshRate != wantHz) {
            lp.preferredDisplayModeId = wantId
            lp.preferredRefreshRate = wantHz
            w.attributes = lp
            AppLog.i(
                "refresh",
                if (mode != null) "强制高刷新率:模式 ${mode.modeId} = ${"%.0f".format(mode.refreshRate)} Hz(当前 ${"%.0f".format(display?.refreshRate ?: 0f)} Hz)"
                else if (on) "屏幕没有 120 Hz 及以上的显示模式,不强制;所有模式 " + runCatching { display!!.supportedModes.joinToString { "${"%.0f".format(it.refreshRate)}Hz" } }.getOrDefault("?")
                else "取消强制高刷新率,由系统决定",
            )
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 35) {
                w.decorView.requestedFrameRate = if (mode != null) View.REQUESTED_FRAME_RATE_CATEGORY_HIGH else View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE
            }
        }
    }

    /** 视频增强视图要释放自己的刷新率请求时:开着强制就退回到强制的模式,而不是交给系统(0)。 */
    fun forcedMode(context: Context, display: Display?): Display.Mode? =
        if ((context.applicationContext as? com.localtg.App)?.container?.settings?.value?.forceHighRefresh == true) targetMode(display) else null

    /** 显示模式变化(接外接屏、切分辨率 / 刷新率)时回调,用来重新套用。返回的 listener 用 [unregister] 取消。 */
    fun register(context: Context, onChange: () -> Unit): DisplayManager.DisplayListener {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) { onChange() }
        }
        dm.registerDisplayListener(l, android.os.Handler(android.os.Looper.getMainLooper()))
        return l
    }

    fun unregister(context: Context, l: DisplayManager.DisplayListener) {
        runCatching { (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(l) }
    }
}
