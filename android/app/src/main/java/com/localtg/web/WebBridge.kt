package com.localtg.web

import android.content.Intent
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebView
import coil3.SingletonImageLoader
import com.localtg.AppContainer
import com.localtg.AppLog
import com.localtg.ViewerActivity
import com.localtg.data.ApiDiskCache
import com.localtg.data.ApiException
import com.localtg.data.AppJson
import com.localtg.data.AppSettings
import com.localtg.data.AppUpdater
import com.localtg.data.DialogView
import com.localtg.data.Item
import com.localtg.data.LocalMedia
import com.localtg.data.Net
import com.localtg.data.Route
import com.localtg.data.Session
import com.localtg.data.SlideGroupOrder
import com.localtg.data.SlideScope
import com.localtg.data.SlideSource
import com.localtg.data.SpeedTest
import com.localtg.data.Tls
import com.localtg.data.ViewerFeed
import com.localtg.data.friendlyError
import com.localtg.data.normalizeAddress
import com.localtg.render.HardwareProbe
import com.localtg.render.Lsfg
import com.localtg.render.LsfgNative
import com.localtg.ui.Hap
import com.localtg.ui.probeDeviceCodecs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 网页界面(android/web,React + HeroUI)和原生之间的桥。
 *
 * JS 调用 `LB.call(id, method, argsJson)`,结果通过 `window.__lb.resolve(id, ok, json)` 异步返回;
 * 原生主动推送用 `window.__lb.emit(name, json)`(登录状态、设置、返回键、查看器关闭、下载进度…)。
 * 数据层(登录 / 证书指纹 / 令牌 / 内网直连 / 本地媒体 / 浏览记录)全部沿用原来的 Kotlin 实现,网页只负责界面。
 */
class WebBridge(private val c: AppContainer, private val web: WebView) {
    private val app = web.context.applicationContext
    private val scope = c.scope
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** 读取媒体权限:由宿主界面注入(要用 Activity 的权限弹窗)。 */
    var requestMediaPermission: (suspend () -> Boolean)? = null
    /** 网页告诉原生"现在按返回键由我处理"(有二级页面 / 弹层打开)。 */
    var onBackEnabled: (Boolean) -> Unit = {}
    /** 状态栏 / 导航栏图标深浅(浅色界面要深色图标)。 */
    var onSystemBars: (Boolean) -> Unit = {}

    private var speedJob: Job? = null

    /** 最近一次的安全区(CSS 像素);网页启动时从 boot 取,之后变化走 insets 事件。 */
    @Volatile var insets: JsonElement = JsonNull

    init {
        // 登录状态、服务器列表变化:推给网页(令牌不出原生)
        scope.launch {
            combine(c.session.servers, c.session.activeId, c.session.session, c.session.local) { _, _, _, _ -> sessionJson() }
                .drop(1).collect { emit("session", it) }
        }
        scope.launch { c.settings.state.drop(1).collect { emit("settings", AppJson.encodeToJsonElement(it)) } }
        scope.launch { Route.current.drop(1).collect { emit("route", JsonPrimitive(it ?: "")) } }
        scope.launch { c.notice.drop(1).collect { emit("notice", JsonPrimitive(it ?: "")) } }
        scope.launch { c.api.syncUnsupported.drop(1).collect { if (it) emit("syncUnsupported", JsonPrimitive(true)) } }
        scope.launch {
            AppUpdater.downloadState.drop(1).collect { d ->
                emit("update", buildJsonObject { put("version", d.version); put("progress", d.progress); put("error", d.error); put("done", d.file != null) })
            }
        }
    }

    @JavascriptInterface
    fun call(id: String, method: String, args: String) {
        scope.launch {
            val a = runCatching { AppJson.parseToJsonElement(args.ifEmpty { "{}" }).jsonObject }.getOrDefault(JsonObject(emptyMap()))
            try {
                reply(id, true, dispatch(method, a))
            } catch (e: CancellationException) {
                reply(id, false, err(e, "已取消"))
            } catch (e: Throwable) {
                if (e !is ApiException) AppLog.w("web", "调用 $method 失败", e)
                reply(id, false, err(e, friendlyError(e)))
            }
        }
    }

    private fun err(e: Throwable, msg: String) = buildJsonObject {
        put("message", msg)
        if (e is ApiException) { put("http", e.http); put("code", e.code) }
    }

    private fun reply(id: String, ok: Boolean, data: JsonElement) =
        js("window.__lb&&window.__lb.resolve(${JsonPrimitive(id)},$ok,$data)")

    fun emit(name: String, data: JsonElement = JsonNull) = js("window.__lb&&window.__lb.emit(${JsonPrimitive(name)},$data)")

    private fun js(code: String) {
        web.post { runCatching { web.evaluateJavascript(code, null) } }
    }

    // ---------------------------------------------------------------- 分发

    private suspend fun dispatch(m: String, a: JsonObject): JsonElement = when (m) {
        "boot" -> boot()
        "settings.update" -> {
            val patch = a["patch"]?.jsonObject ?: JsonObject(emptyMap())
            val cur = AppJson.encodeToJsonElement(c.settings.value).jsonObject
            val n = AppJson.decodeFromJsonElement<AppSettings>(JsonObject(cur + patch))
            c.settings.update { n }
            AppJson.encodeToJsonElement(n)
        }
        "settings.reset" -> { c.settings.reset(); AppJson.encodeToJsonElement(c.settings.value) }

        "http" -> http(a)
        "peek" -> peek(a.s("path"))

        "session.probe" -> probe(a.s("address"))
        "session.trust" -> { c.session.trust(a.s("fp")); JsonNull }
        "session.info" -> AppJson.encodeToJsonElement(c.api.serverInfo(a.s("url")))
        "session.login" -> {
            val url = a.s("url")
            val r = c.api.login(url, a.s("user").trim(), a.s("pass"), "${Build.MANUFACTURER} ${Build.MODEL}")
            c.session.save(Session(url, r.token), a.s("address"), a.s("user").trim(), a.sOrNull("fp"))
            c.notice.value = null
            sessionJson()
        }
        "session.local" -> {
            val ok = LocalMedia.hasPermission(app) || (requestMediaPermission?.invoke() == true)
            if (!ok) throw IllegalStateException("需要允许读取照片和视频,才能浏览手机上的本地媒体(可以在系统设置里授予)")
            c.session.enterLocal(); c.local.preload(); sessionJson()
        }
        "session.switch" -> { c.session.switchTo(a.s("id")); sessionJson() }
        "session.deactivate" -> { c.session.deactivate(); sessionJson() }
        "session.rename" -> { c.session.rename(a.s("id"), a.s("name")); sessionJson() }
        "session.remove" -> { c.session.remove(a.s("id")); sessionJson() }
        "session.logout" -> {
            val id = a.sOrNull("id") ?: c.session.activeId.value
            if (id == c.session.activeId.value && !c.isLocal) c.api.logout()
            if (id != null) c.session.forget(id)
            sessionJson()
        }
        "session.lastAddress" -> JsonPrimitive(c.session.lastAddress())

        "route.probe" -> { c.probeRoute(); JsonPrimitive(Route.current.value ?: "") }
        "caps" -> { if (!c.isLocal) runCatching { c.api.refreshCaps() }; AppJson.encodeToJsonElement(c.api.caps) }

        "viewer.open" -> { openViewer(a); JsonNull }

        "view.load" -> loadView(a.s("dialogId"))
        "view.save" -> { saveView(a.s("dialogId"), AppJson.decodeFromJsonElement(a["view"]!!), a.b("now")); JsonNull }
        "view.recent" -> JsonObject(c.viewState.recentNames(c.session.ns()).first().mapValues { JsonPrimitive(it.value) })
        "view.history" -> {
            val id = a.s("dialogId")
            AppJson.encodeToJsonElement(if (c.isLocal) com.localtg.data.BrowseHistoryResult(c.viewState.history(id)) else c.api.browseHistory(id))
        }
        "view.checkpoint" -> {
            val id = a.s("dialogId"); val v = AppJson.decodeFromJsonElement<DialogView>(a["view"]!!).copy(device = Build.MODEL)
            if (c.isLocal) c.viewState.checkpoint(id, v, manual = true) else c.api.saveBrowseHistory(id, v)
            JsonNull
        }
        "view.deleteHistory" -> {
            val id = a.s("dialogId"); val at = a["at"]?.jsonPrimitive?.longOrNull
            if (c.isLocal) c.viewState.deleteHistory(id, at) else c.api.deleteBrowseHistory(id, at)
            JsonNull
        }

        "storage.info" -> withContext(Dispatchers.IO) {
            buildJsonObject {
                put("images", (SingletonImageLoader.get(app).diskCache?.size ?: 0L) + WebMedia.cacheSize(app))
                put("playback", c.playback.count())
            }
        }
        "storage.clearImages" -> withContext(Dispatchers.IO) {
            val l = SingletonImageLoader.get(app); l.diskCache?.clear(); l.memoryCache?.clear()
            WebMedia.clear(app)
            web.post { web.clearCache(true) }
            JsonNull
        }
        "storage.clearViews" -> { c.viewState.clearAll(); if (!c.isLocal) runCatching { c.api.clearSyncedViews() }; JsonNull }
        "storage.clearPlayback" -> { c.playback.clearAll(); if (!c.isLocal) runCatching { c.api.clearSyncedPlayback() }; JsonNull }
        "random.reset" -> {
            if (c.isLocal) throw IllegalStateException("本地媒体没有服务端随机序列")
            c.api.randomReset(); c.settings.update { copy(randomSeed = 0) }; JsonNull
        }

        "log.info" -> buildJsonObject {
            put("size", AppLog.sizeBytes())
            put("crashes", JsonArray(AppLog.crashFiles().map { JsonPrimitive(it.name) }))
        }
        "log.tail" -> JsonPrimitive(withContext(Dispatchers.IO) { AppLog.tail(a.i("max") ?: 300_000) })
        "log.copy" -> JsonPrimitive(AppLog.copyRecent(app))
        "log.share" -> { AppLog.share(web.context); JsonNull }
        "log.clear" -> { AppLog.clear(); JsonNull }
        "crash.dismiss" -> { AppLog.dismissPendingCrash(); JsonNull }

        "app.restart" -> { restart(); JsonNull }
        "app.open" -> { web.context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(a.s("url")))); JsonNull }
        "app.haptic" -> { c.haptics.perform(runCatching { Hap.valueOf(a.s("kind")) }.getOrDefault(Hap.Tick)); JsonNull }
        "app.back" -> { onBackEnabled(a.b("enabled")); JsonNull }
        "app.bars" -> { onSystemBars(a.b("light")); JsonNull }
        "app.changelog" -> changelog()

        "speed.start" -> { startSpeedTest(a.i("seconds") ?: 15, a.i("threads") ?: 1); JsonNull }
        "speed.stop" -> { speedJob?.cancel(); JsonNull }

        "hw.probe" -> {
            val r = withContext(Dispatchers.Default) { HardwareProbe.run(app) { step -> emit("hwProgress", JsonPrimitive(step)) } }
            c.settings.update { copy(hwReport = AppJson.encodeToString(com.localtg.render.HwReport.serializer(), r)) }
            AppJson.encodeToJsonElement(com.localtg.render.HwReport.serializer(), r)
        }
        "lsfg.status" -> buildJsonObject {
            put("supported", Lsfg.deviceSupported)
            put("lib", LsfgNative.load())
            put("dllInAssets", Lsfg.dllInAssets(app))
            put("bundled", Lsfg.dllBundled(app))
            put("cacheReady", Lsfg.cacheReady(app))
            put("state", Lsfg.state)
            put("error", Lsfg.error)
        }
        "lsfg.prepare" -> { Lsfg.prepareAsync(app); JsonNull }
        "lsfg.reset" -> { Lsfg.reset(app); Lsfg.prepareAsync(app); JsonNull }
        "codecs" -> withContext(Dispatchers.Default) {
            buildJsonArray {
                probeDeviceCodecs().forEach { g ->
                    add(buildJsonObject {
                        put("label", g.label); put("mime", g.mime)
                        put("decoders", buildJsonArray { g.decoders.forEach { d -> add(buildJsonObject { put("name", d.name); put("hardware", d.hardware); put("detail", d.detail) }) } })
                    })
                }
            }
        }

        "update.check" -> checkUpdate()
        "update.download" -> { startDownload(); JsonNull }
        "update.pending" -> pendingUpdate()
        "update.install" -> {
            val p = withContext(Dispatchers.IO) { AppUpdater.store(app).pending(version()) } ?: throw IllegalStateException("没有已下载的安装包")
            JsonPrimitive(AppUpdater.install(web.context, p.file))
        }
        "update.sources" -> buildJsonArray {
            AppUpdater.probeSources { list -> emit("updateSources", sourcesJson(list)) }.forEach { add(sourceJson(it)) }
        }

        else -> throw IllegalArgumentException("未知的调用:$m")
    }

    // ---------------------------------------------------------------- 启动 / 登录

    private fun version() = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()

    private suspend fun boot(): JsonElement {
        c.session.loaded.first { it }
        val crash = AppLog.pendingCrash()
        val night = (app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        return buildJsonObject {
            put("version", version())
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("model", Build.MODEL)
            put("android", "Android ${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT})")
            put("systemDark", night)
            put("session", sessionJson())
            put("settings", AppJson.encodeToJsonElement(c.settings.value))
            put("caps", AppJson.encodeToJsonElement(c.api.caps))
            put("route", Route.current.value ?: "")
            put("notice", c.notice.value ?: "")
            put("crash", crash?.let { AppLog.crashSummary(it) } ?: "")
            put("hasCrash", crash != null)
            put("insets", insets)
        }
    }

    private fun sessionJson(): JsonElement = buildJsonObject {
        val s = c.session.session.value
        put("activeId", c.session.activeId.value ?: "")
        put("loggedIn", s != null)
        put("local", c.session.local.value)
        put("baseUrl", if (c.session.local.value) "" else s?.baseUrl ?: "")
        put("pin", c.session.pin.value ?: "")
        put("servers", buildJsonArray {
            c.session.servers.value.forEach { e ->
                add(buildJsonObject {
                    put("id", e.id); put("name", e.name); put("kind", e.kind); put("host", e.host)
                    put("baseUrl", e.baseUrl); put("address", e.address); put("user", e.user); put("loggedIn", e.loggedIn)
                })
            }
        })
    }

    /** 规范化地址;HTTPS 先读证书指纹,交给网页决定是否需要用户核对。 */
    private suspend fun probe(address: String): JsonElement {
        val url = normalizeAddress(address)
        var fp = ""
        if (url.startsWith("https://")) {
            val hu = url.toHttpUrl()
            fp = Tls.probe(hu.host, hu.port)
        }
        return buildJsonObject {
            put("url", url); put("fp", fp)
            put("trusted", fp.isEmpty() || c.session.isTrusted(fp))
        }
    }

    // ---------------------------------------------------------------- 通用接口请求

    private fun base(): String = c.session.session.value?.baseUrl ?: throw IllegalStateException("未登录")

    /**
     * 发往当前服务器(或本地媒体)的接口请求,令牌 / 内网直连 / 证书校验都由原来的 OkHttp 拦截器处理。
     * cache = true 时走带 ETag 的磁盘缓存(和原生列表页同一份缓存);GET 网络失败自动重试两次。
     */
    private suspend fun http(a: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val method = a.sOrNull("method") ?: "GET"
        val path = a.s("path")
        val url = base() + path
        val body = a["body"]?.takeIf { it !is JsonNull }?.toString()
        val cached = a.b("cache") && method == "GET"
        val s = c.session.session.value
        val key = (s?.baseUrl ?: "") + "|" + (s?.token?.take(12) ?: "") + "|" + url
        val saved = if (cached) ApiDiskCache.read(key) else null
        val b = Request.Builder().url(url)
        if (saved != null) b.header("If-None-Match", saved.etag)
        when (method) {
            "GET" -> b.get()
            "DELETE" -> if (body != null) b.delete(body.toRequestBody(jsonType)) else b.delete()
            else -> b.method(method, (body ?: "{}").toRequestBody(jsonType))
        }
        var attempt = 0
        while (true) {
            if (!Net.online) Net.awaitOnline(10_000)
            try {
                c.apiHttp.newCall(b.build()).execute().use { r ->
                    val text = r.body.string()
                    if (r.code == 304 && saved != null) return@withContext AppJson.parseToJsonElement(saved.body)
                    if (!r.isSuccessful) {
                        val code = Regex("\"code\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: "http.${r.code}"
                        val msg = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: "HTTP ${r.code}"
                        throw ApiException(r.code, code, msg)
                    }
                    if (cached) r.header("ETag")?.let { ApiDiskCache.write(key, it, text) }
                    return@withContext if (text.isBlank()) JsonNull else runCatching { AppJson.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
                }
            } catch (e: IOException) {
                if (e is ApiException || method != "GET" || ++attempt > 2) throw e
                kotlinx.coroutines.delay(attempt * 700L)
            }
        }
        @Suppress("UNREACHABLE_CODE") JsonNull
    }

    /** 磁盘缓存里上次的内容(先显示,不等网络)。 */
    private fun peek(path: String): JsonElement {
        val s = c.session.session.value ?: return JsonNull
        val saved = ApiDiskCache.read(s.baseUrl + "|" + s.token.take(12) + "|" + s.baseUrl + path) ?: return JsonNull
        return runCatching { AppJson.parseToJsonElement(saved.body) }.getOrDefault(JsonNull)
    }

    // ---------------------------------------------------------------- 浏览位置

    /** 本机和服务器各有一份记录时,以最后更新的为准(和原来的群页一致);本机更新的推回服务器。 */
    private suspend fun loadView(dialogId: String): JsonElement {
        val local = c.viewState.load(dialogId)
        val remote = if (!c.isLocal) kotlinx.coroutines.withTimeoutOrNull(2500) { c.api.getDialogViewRemote(dialogId) } else null
        val chosen = if (remote != null && (local == null || remote.savedAt >= local.savedAt)) remote.view else local
        if (remote != null && local != null && local.savedAt > remote.savedAt) {
            c.scope.launch { runCatching { c.api.putDialogView(dialogId, local, local.savedAt) } }
        }
        val from = if (remote != null && chosen === remote.view && remote.view.device.isNotEmpty() && remote.view.device != Build.MODEL) remote.view.device else ""
        return buildJsonObject {
            put("view", chosen?.let { AppJson.encodeToJsonElement(it) } ?: JsonNull)
            put("from", from)
        }
    }

    private suspend fun saveView(dialogId: String, view: DialogView, now: Boolean) {
        if (!c.settings.value.rememberPosition) return
        val v = view.copy(device = Build.MODEL)
        c.viewState.save(dialogId, v)
        if (c.isLocal) { c.viewState.checkpoint(dialogId, v); return }
        val startBase = c.session.session.value?.baseUrl
        c.scope.launch {
            if (!now) kotlinx.coroutines.delay(1500)
            if (c.isLocal || c.session.session.value?.baseUrl != startBase) return@launch
            runCatching { c.api.putDialogView(dialogId, v) }.onFailure { AppLog.d("web", "同步浏览位置失败: ${it.message}") }
        }
    }

    // ---------------------------------------------------------------- 查看器(原生)

    /**
     * 打开全屏查看器 / 播放器:图片缩放、视频播放、超分 / 补帧都在原生里(OpenGL / ExoPlayer),网页只传条目和"怎么继续加载"。
     * source.kind:dialog(群里的历史)/ search(搜索结果)/ random(随机浏览)。
     */
    private fun openViewer(a: JsonObject) {
        val items = AppJson.decodeFromJsonElement<List<Item>>(a["items"]!!)
        val index = a.i("index") ?: 0
        val src = a["source"]?.takeIf { it is JsonObject }?.jsonObject
        val types = src?.sOrNull("types") ?: "photo,video,gif"
        val feed = when (src?.sOrNull("kind")) {
            "dialog" -> {
                val id = src.s("dialogId"); val sort = src.s("sort"); val dir = src.s("dir")
                ViewerFeed(
                    items, originDialogId = id,
                    slideSource = SlideSource(SlideScope(types, dialog = id), SlideGroupOrder(sort, dir)),
                ) { cursor, lastId ->
                    if (cursor == null) {
                        val r = c.api.history(id, sort, dir, types, null, 61, around = lastId)
                        val i = r.items.indexOfFirst { it.id == lastId }
                        (if (i >= 0) r.items.drop(i + 1) else r.items) to r.nextCursor
                    } else c.api.history(id, sort, dir, types, cursor, 60).let { it.items to it.nextCursor }
                }
            }
            "search" -> {
                val q = src.s("q"); val dialog = src.sOrNull("dialog"); val start = src.sOrNull("cursor")
                if (start == null) ViewerFeed(items, slideSource = SlideSource(SlideScope(types, dialog = dialog, search = q)))
                else ViewerFeed(items, slideSource = SlideSource(SlideScope(types, dialog = dialog, search = q))) { cursor, _ ->
                    c.api.search(q, types, cursor ?: start, 60, dialog).let { it.items to it.nextCursor }
                }
            }
            "random" -> {
                val roots = src.sOrNull("roots"); val seed = src["seed"]?.jsonPrimitive?.longOrNull ?: 0L
                var batch = src.i("batch") ?: 1
                ViewerFeed(items, originDialogId = "random", slideSource = SlideSource(SlideScope(types, roots = roots))) { _, _ ->
                    c.api.random(types, 60, roots, seed, batch++).items to "more"
                }
            }
            else -> ViewerFeed(items)
        }
        c.viewerFeed = feed
        ViewerActivity.start(web.context, index) { last ->
            emit("viewerClosed", buildJsonObject {
                put("origin", feed.originDialogId ?: "")
                put("itemId", last?.let { feed.returnPosition(it)?.second ?: it.id } ?: "")
                put("items", if (feed.originDialogId == "random") AppJson.encodeToJsonElement(feed.items.toList()) else JsonNull)
            })
        }
    }

    // ---------------------------------------------------------------- 测速 / 更新 / 其它

    private fun startSpeedTest(seconds: Int, threads: Int) {
        speedJob?.cancel()
        speedJob = scope.launch {
            val st = SpeedTest(c)
            fun tick(phase: String, el: Long, inst: Double, avg: Double) =
                emit("speed", buildJsonObject { put("phase", phase); put("elapsed", el); put("now", inst); put("avg", avg) })
            fun flow(f: SpeedTest.Flow) = buildJsonObject {
                put("bytes", f.bytes); put("ms", f.ms); put("avg", f.avgMbps); put("stable", f.stableMbps); put("peak", f.peakMbps)
            }
            try {
                emit("speed", buildJsonObject { put("phase", "latency") })
                val lat = st.latency { i, _ -> emit("speed", buildJsonObject { put("phase", "latency"); put("done", i + 1) }) }
                emit("speed", buildJsonObject {
                    put("phase", "latencyDone"); put("avg", lat.avg); put("min", lat.min); put("max", lat.max)
                    put("jitter", lat.jitter); put("lost", lat.lost); put("samples", lat.samples.size)
                })
                val down = st.download(seconds, threads) { el, inst, avg -> tick("download", el, inst, avg) }
                emit("speed", buildJsonObject { put("phase", "downloadDone"); put("result", flow(down)) })
                val up = st.upload(seconds, threads) { el, inst, avg -> tick("upload", el, inst, avg) }
                emit("speed", buildJsonObject { put("phase", "uploadDone"); put("result", flow(up)) })
                emit("speed", buildJsonObject { put("phase", "done") })
            } catch (e: CancellationException) {
                emit("speed", buildJsonObject { put("phase", "stopped") }); throw e
            } catch (e: Exception) {
                emit("speed", buildJsonObject { put("phase", "error"); put("message", (e as? ApiException)?.message ?: friendlyError(e)) })
            }
        }
    }

    private var lastRelease: AppUpdater.Release? = null

    private suspend fun serverRelease(): AppUpdater.Release? {
        val i = c.api.appUpdate(check = true)
        if (i.latest.isEmpty()) return null
        val base = "https://github.com/${AppUpdater.REPO}/releases/latest/download/"
        return AppUpdater.Release(
            tag = "", version = i.latest, notes = i.notes.trim().take(1500), apkUrl = base + "LocalBrowse-${i.latest}.apk", sumUrl = base + "SHA256SUMS.txt",
            size = if (i.ready) i.size else 0, sha = i.sha256.lowercase().takeIf { i.ready && it.length == 64 },
            server = AppUpdater.ServerSrc(c.api.appUpdateApkUrl(), i.latest, if (i.ready) i.sha256 else "", i.ready, i.progress, if (i.ready) i.size else 0),
        )
    }

    private suspend fun checkUpdate(): JsonElement {
        val ver = version()
        val r = AppUpdater.latest(if (c.isLocal) null else suspend { serverRelease() }, ver)
        lastRelease = r
        return buildJsonObject {
            put("current", ver)
            if (r != null) {
                put("version", r.version)
                put("newer", AppUpdater.compare(r.version, ver) > 0)
                put("notes", com.localtg.data.cleanMarkdown(r.notes))
                put("fromServer", r.server?.ready == true)
                put("serverProgress", r.server?.progress ?: -1)
            }
        }
    }

    private fun startDownload() {
        val r = lastRelease ?: throw IllegalStateException("请先检查更新")
        AppUpdater.startDownload(c.scope, app, r, c.apiHttp) { if (c.isLocal) null else serverRelease()?.server }
    }

    private suspend fun pendingUpdate(): JsonElement {
        val d = AppUpdater.downloadState.value
        val p = withContext(Dispatchers.IO) { AppUpdater.store(app).pending(version()) }
        return buildJsonObject {
            put("pending", p?.version ?: "")
            put("downloading", d.progress >= 0); put("progress", d.progress); put("version", d.version); put("error", d.error)
        }
    }

    private fun sourceJson(s: AppUpdater.SourceStatus) = buildJsonObject {
        put("name", s.name); put("prefix", s.prefix); put("ok", s.ok); put("ms", s.ms); put("note", s.note); put("testing", s.testing)
    }
    private fun sourcesJson(l: List<AppUpdater.SourceStatus>) = JsonArray(l.map { sourceJson(it) })

    /** assets/changelog.md 的「手机端」部分:每个「### 版本」及其条目(新 → 旧)。 */
    private fun changelog(): JsonElement {
        val text = runCatching { app.assets.open("changelog.md").bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull() ?: return JsonArray(emptyList())
        val out = ArrayList<Pair<String, MutableList<String>>>()
        var inSection = false
        for (raw in text.lines()) {
            val l = raw.trimEnd()
            when {
                l.startsWith("## ") -> inSection = l.removePrefix("## ").contains("手机端")
                inSection && l.startsWith("### ") -> out += l.removePrefix("### ").trim() to mutableListOf()
                inSection && out.isNotEmpty() && Regex("^\\s*[-*]\\s+").containsMatchIn(l) ->
                    out.last().second += com.localtg.data.inlineClean(l.replace(Regex("^\\s*[-*]\\s+"), "").trim())
            }
        }
        return JsonArray(out.map { (v, lines) -> buildJsonObject { put("version", v); put("items", JsonArray(lines.map { JsonPrimitive(it) })) } })
    }

    private fun restart() {
        val i = app.packageManager.getLaunchIntentForPackage(app.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) ?: return
        app.startActivity(i)
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

private fun JsonObject.sOrNull(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.s(k: String): String = sOrNull(k) ?: throw IllegalArgumentException("缺少参数 $k")
private fun JsonObject.b(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
private fun JsonObject.i(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
