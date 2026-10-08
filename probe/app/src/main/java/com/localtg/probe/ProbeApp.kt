package com.localtg.probe

import android.app.Application
import com.localtg.AppLog
import com.localtg.render.Assets

/** 回放测试复用主程序的渲染管线,它需要 Assets.app 和 AppLog;每个进程(界面 / :probe / :play)启动时都初始化一次。 */
class ProbeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Assets.app = applicationContext
        AppLog.init(this, "debug")
    }
}
