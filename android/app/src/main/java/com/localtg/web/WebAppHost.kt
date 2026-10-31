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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 主界面:整个应用的界面是一个 WebView(React + HeroUI,源码在 android/web,打包进 assets/web)。
 * 原生只负责数据层、全屏查看器 / 播放器和系统集成(返回键、状态栏、权限、安全区)。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebAppHost(c: AppContainer) {
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
        WebBridge(c, web).also { b ->
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

    BackHandler(enabled = backEnabled) { bridge.emit("back") }
    DisposableEffect(Unit) { onDispose { web.destroy() } }
    AndroidView({ web }, Modifier.fillMaxSize().imePadding())
}
