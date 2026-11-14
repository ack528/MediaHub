package com.localtg

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import com.localtg.ui.tg.LocalSettings
import com.localtg.ui.tg.TgTheme

/**
 * 所有界面 Activity 的基类:统一的主题 / 状态栏 / 窗口背景色,以及小窗(画中画)相关的回调。
 *
 * 窗口背景色要和页面一致:新 Activity 启动时,系统转场动画先显示的是窗口背景,
 * Compose 内容画出来之前如果是别的颜色(比如深色主题下的白底)就会闪一下,看起来很"生硬"。
 */
abstract class TgActivity : ComponentActivity() {
    protected val container get() = (application as App).container

    @Composable
    protected abstract fun Content()

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

    /** 根页面(从桌面打开 / 退出应用)不覆盖系统的启动动画。 */
    protected open val customTransitions = true

    /**
     * 页面转场按 设置 → 外观 → 动画 → 界面动画:Android 14+ 用 overrideActivityTransition(和预测性返回兼容),
     * 更早的系统在打开 / 关闭时 overridePendingTransition。系统风格不覆盖。
     */
    private fun applyTransitions() {
        if (!customTransitions) return
        val style = container.settings.value.motion
        val a = com.localtg.ui.Motion.activityAnims(style)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            if (a == null) {
                clearOverrideActivityTransition(OVERRIDE_TRANSITION_OPEN)
                clearOverrideActivityTransition(OVERRIDE_TRANSITION_CLOSE)
            } else {
                overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, a[0], a[1])
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, a[2], a[3])
            }
        } else if (a != null) {
            @Suppress("DEPRECATION") overridePendingTransition(a[0], a[1])
        }
    }

    override fun finish() {
        // 在这个页面里改了界面动画(设置页):关闭时就按新的来
        if (customTransitions && android.os.Build.VERSION.SDK_INT >= 34) applyTransitions()
        super.finish()
        if (customTransitions && android.os.Build.VERSION.SDK_INT < 34) {
            com.localtg.ui.Motion.activityAnims(container.settings.value.motion)?.let { @Suppress("DEPRECATION") overridePendingTransition(it[2], it[3]) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTransitions()
        super.onCreate(savedInstanceState)
        // 状态栏 / 导航栏透明;图标深浅由 TgTheme 按顶栏颜色设置(白天白色顶栏 = 深色图标,夜间深蓝顶栏 = 浅色图标)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val c = container
        applyWindowBackground(c.settings.value.theme)
        applySecure(c.settings.value.screenSecure)
        applyHighRefresh()
        displayListener = com.localtg.render.HighRefresh.register(this) { applyHighRefresh() }
        setContent {
            val st by c.settings.state.collectAsState()
            LaunchedEffect(st.theme) { applyWindowBackground(st.theme) }
            LaunchedEffect(st.screenSecure) { applySecure(st.screenSecure) }
            LaunchedEffect(st.forceHighRefresh) { applyHighRefresh() }
            val base = androidx.compose.ui.platform.LocalDensity.current
            val density = remember(base, st.fontScale) { androidx.compose.ui.unit.Density(base.density, base.fontScale * st.fontScale) }
            TgTheme(st.theme) {
                CompositionLocalProvider(
                    LocalSettings provides st,
                    com.localtg.ui.LocalHaptics provides c.haptics,
                    androidx.compose.ui.platform.LocalDensity provides density,
                ) { Content() }
            }
        }
    }

    private var displayListener: android.hardware.display.DisplayManager.DisplayListener? = null

    /** 强制保持屏幕 120 Hz 及以上(设置 → 外观 → 屏幕);每次回到前台、显示模式变化时重新套用,系统(省电 / 画中画 / 多窗口)可能已经把请求清掉。 */
    private fun applyHighRefresh() = com.localtg.render.HighRefresh.apply(this, container.settings.value.forceHighRefresh)

    override fun onResume() {
        super.onResume()
        applyHighRefresh()
    }

    override fun onDestroy() {
        displayListener?.let { com.localtg.render.HighRefresh.unregister(this, it) }
        super.onDestroy()
    }

    /** 隐私:禁止截屏 / 录屏,同时最近任务里不显示画面。 */
    private fun applySecure(on: Boolean) {
        if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun applyWindowBackground(theme: String) {
        val night = when (theme) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        window.setBackgroundDrawable(windowBackground(night))
    }

    /** 窗口底色:系统转场开头、Compose 第一帧画出来之前看到的就是它,要和页面一致,否则会闪一下。 */
    protected open fun windowBackground(night: Boolean): android.graphics.drawable.Drawable =
        ColorDrawable(if (night) 0xFF1D2733.toInt() else 0xFFFFFFFF.toInt())

    /** 二级页面(聊天 / 设置 / 搜索):登录失效(401)或退出登录后,自己关掉,露出下面的登录页。 */
    @Composable
    protected fun FinishWhenLoggedOut() {
        val c = container
        val loaded by c.session.loaded.collectAsState()
        val session by c.session.session.collectAsState()
        val active by c.session.activeId.collectAsState()
        val opened = androidx.compose.runtime.remember { c.session.activeId.value } // 打开页面时的服务器
        LaunchedEffect(Unit) { if (!c.session.loaded.value) c.session.load() }
        // 登录失效 / 退出登录 / 切换到别的服务器后,这一页的内容已经不属于当前服务器,自己关掉
        LaunchedEffect(loaded, session, active) { if (loaded && (session == null || active != opened)) finish() }
    }
}

/** 首页:React + HeroUI 网页界面(见 [com.localtg.web.WebAppHost]);以前的 Compose 页面不再从这里进入。 */
class MainActivity : TgActivity() {
    override val customTransitions = false

    /** 和网页的 HeroUI 背景色(--background)一致:WebView 第一帧画出来之前不闪。 */
    override fun windowBackground(night: Boolean): android.graphics.drawable.Drawable = webBackground(night)

    @Composable
    override fun Content() = com.localtg.web.WebAppHost(container)
}

private fun webBackground(night: Boolean) = ColorDrawable(if (night) 0xFF232326.toInt() else 0xFFEDEDEF.toInt())

/**
 * 网页界面的二级页面(文件夹 / 搜索 / 随机浏览 / 设置的各个分类):和以前一样每个页面一个 Activity,
 * 打开 / 返回都是系统的转场(设置 → 外观 → 动画 → 界面动画),侧滑返回是系统的预测性返回。
 */
class WebPageActivity : TgActivity() {
    override fun windowBackground(night: Boolean): android.graphics.drawable.Drawable = webBackground(night)

    /** 网页第一屏渲染好了:之前不画第一帧(系统转场等它),滑进来的就是完整的页面,不是一块空白。最多等 [HOLD_MS]。 */
    @Volatile private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = findViewById<android.view.View>(android.R.id.content)
        val start = android.os.SystemClock.uptimeMillis()
        content.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!ready && android.os.SystemClock.uptimeMillis() - start < HOLD_MS) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
    }

    @Composable
    override fun Content() {
        FinishWhenLoggedOut()
        val page = remember { runCatching { com.localtg.data.AppJson.parseToJsonElement(intent.getStringExtra(EXTRA_PAGE) ?: "null") }.getOrDefault(kotlinx.serialization.json.JsonNull) }
        com.localtg.web.WebAppHost(container, page, onReady = { ready = true })
    }

    companion object {
        private const val EXTRA_PAGE = "page"
        private const val HOLD_MS = 450L

        fun start(ctx: Context, page: kotlinx.serialization.json.JsonElement) =
            ctx.startActivity(Intent(ctx, WebPageActivity::class.java).putExtra(EXTRA_PAGE, page.toString()))
    }
}
