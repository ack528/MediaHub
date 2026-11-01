package com.localtg.web

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshotFlow
import com.localtg.ui.LocalBackdrop
import com.localtg.ui.LocalHero
import com.localtg.ui.ViewerNavHost
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.localtg.AppContainer
import com.localtg.AppLog
import com.localtg.data.LocalMedia
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 一个页面的界面:WebView(React + HeroUI,源码在 android/web,打包进 assets/web)。
 * 和以前一样每个页面是一个 Activity([page] = 要显示的页面,JsonNull = 首页),转场和返回手势都是系统的;
 * 点开媒体时原生的查看器从缩略图位置展开(以前的 [ViewerNavHost],列表退后、拖拽关闭都一样)。
 * 原生负责数据层、查看器 / 播放器和系统集成(返回键、状态栏、权限、安全区)。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebAppHost(c: AppContainer, page: JsonElement = JsonNull, onReady: () -> Unit = {}) {
    val ctx = LocalContext.current
    val view = LocalView.current
    var backEnabled by remember { mutableStateOf(false) }

    // Android 17+:访问局域网需要运行时授权
    val lanPermission = "android.permission.ACCESS_LOCAL_NETWORK"
    val lanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 37 && ctx.checkSelfPermission(lanPermission) != PackageManager.PERMISSION_GRANTED) lanLauncher.launch(lanPermission)
    }
    val pending = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending[0]?.complete(LocalMedia.hasPermission(ctx)); pending[0] = null
    }

    val web = remember {
        WebView(ctx).apply {
            // 必须铺满:AndroidView 默认给 WRAP_CONTENT,WebView 会按内容定高,网页里 height:100% 变成 0,整页被裁掉(白屏)
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
            overScrollMode = WebView.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.textZoom = (c.settings.value.fontScale * 100).toInt()
            settings.mediaPlaybackRequiresUserGesture = true
            if ((ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) WebView.setWebContentsDebuggingEnabled(true)
        }
    }
    val media = remember { WebMedia(ctx, c) }
    val bridge = remember {
        WebBridge(c, web, media).also { b ->
            b.page = page
            web.addJavascriptInterface(b, "LB")
            b.requestMediaPermission = {
                val d = CompletableDeferred<Boolean>()
                pending[0] = d
                mediaLauncher.launch(LocalMedia.permissions())
                d.await()
            }
        }
    }
    bridge.onBackEnabled = { backEnabled = it }
    bridge.onReady = onReady
    bridge.onSystemBars = { light ->
        (ctx as? android.app.Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
        }
    }

    // 读取已保存的服务器和登录(以前由 AppRoot 做);网页的 boot 要等它读完
    LaunchedEffect(Unit) { if (!c.session.loaded.value) c.session.load() }

    LaunchedEffect(Unit) {
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? = media.handle(request)
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == WebMedia.HOST) return false
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)) }
                return true
            }
            // 界面打不开时排查用:页面加载 / 出错 / 渲染进程退出都写进日志
            override fun onPageFinished(v: WebView, url: String) { AppLog.i("web", "页面加载完成 $url") }
            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                AppLog.w("web", "加载失败 ${request.url}: ${error.errorCode} ${error.description}")
            }
            override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (!request.url.path.orEmpty().startsWith("/srv/")) AppLog.w("web", "加载失败 ${request.url}: HTTP ${response.statusCode}")
            }
            override fun onRenderProcessGone(v: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                AppLog.e("web", "网页渲染进程退出 crash=${detail.didCrash()}")
                return false
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR || m.messageLevel() == ConsoleMessage.MessageLevel.WARNING)
                    AppLog.w("web", "${m.message()} (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})")
                return true
            }
        }
        web.loadUrl(WebMedia.ORIGIN + "index.html")
    }

    // 安全区(状态栏 / 导航栏)以 CSS 像素推给网页;键盘弹出时整个 WebView 缩小(imePadding)
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val bars = WindowInsets.systemBars
    val top = with(density) { bars.getTop(this).toDp().value }
    val bottom = with(density) { bars.getBottom(this).toDp().value }
    val left = with(density) { bars.getLeft(this, dir).toDp().value }
    val right = with(density) { bars.getRight(this, dir).toDp().value }
    LaunchedEffect(top, bottom, left, right) {
        val v = buildJsonObject { put("top", top); put("bottom", bottom); put("left", left); put("right", right) }
        bridge.insets = v
        bridge.emit("insets", v)
    }
    LaunchedEffect(Unit) {
        c.settings.state.collect { s -> web.settings.textZoom = (s.fontScale * 100).toInt() }
    }

    // 返回键:网页有弹层 / 非首个标签时交给网页;否则是系统的返回(关掉这个页面,系统的预测性返回动画)
    BackHandler(enabled = backEnabled) { bridge.emit("back") }

    // 页面回到前台 / 被盖住(例如从文件夹返回后,首页刷新"最近浏览")
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> bridge.emit("pageActive", JsonPrimitive(true))
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> bridge.emit("pageActive", JsonPrimitive(false))
                else -> {}
            }
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    DisposableEffect(Unit) { onDispose { bridge.dispose(); web.destroy() } }

    val st = c.settings.state.collectAsState().value
    val night = when (st.theme) { "dark" -> true; "light" -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }
    ViewerNavHost(c, onClosed = { bridge.viewerClosed(it) }) { open ->
        bridge.openViewerAt = open
        val hero = LocalHero.current
        bridge.onHeroBounds = { id, src -> hero?.bounds?.set(id, src) }
        // 查看器正在看的那一项:网页把列表里的这一格藏起来、滚到看得见的地方并报告位置(返回动画落到这里)
        LaunchedEffect(hero) {
            if (hero != null) snapshotFlow { hero.hiddenId }.collect { bridge.emit("viewerCurrent", JsonPrimitive(it ?: "")) }
        }
        // 列表退后缩小时四周露出的底色:和网页背景(HeroUI --background)一样
        LocalBackdrop.current?.below = listOf(androidx.compose.ui.graphics.Color(if (night) 0xFF09090B else 0xFFF5F5F5))
        AndroidView({ web }, Modifier.fillMaxSize().imePadding())
    }
}
