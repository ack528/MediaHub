package com.localtg.render

import android.content.Context
import android.view.Display

/** 检测程序里的桩:主程序的 HighRefresh 依赖 App / 设置,这里不强制刷新率。 */
object HighRefresh {
    fun forcedMode(context: Context, display: Display?): Display.Mode? = null
}
