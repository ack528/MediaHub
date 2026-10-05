package com.localtg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.localtg.ui.AppRoot
import com.localtg.ui.tg.TgTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as App).container

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        container.inPip.value = isInPictureInPictureMode
        AppLog.i("pip", "小窗模式=$isInPictureInPictureMode")
    }

    /** 播放视频时按 Home 键:设置里开了"离开应用时自动小窗"就进入画中画。 */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val c = container
        if (c.settings.value.autoPip && c.videoPlaying && c.videoAspect > 0f) {
            val r = c.videoAspect.coerceIn(0.5f, 2.3f)
            runCatching {
                enterPictureInPictureMode(
                    android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational((r * 1000).toInt(), 1000)).build(),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 顶栏是 Telegram 的蓝色/深蓝,状态栏图标用浅色
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val container = this.container
        setContent {
            val st by container.settings.state.collectAsState()
            TgTheme(st.theme) {
                androidx.compose.runtime.CompositionLocalProvider(com.localtg.ui.tg.LocalSettings provides st) { AppRoot(container) }
            }
        }
    }
}
