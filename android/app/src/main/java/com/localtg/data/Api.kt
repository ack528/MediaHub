package com.localtg.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val http: Int, val code: String, message: String) : IOException(message)

val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** 给发往已登录服务器的请求加上 Bearer 令牌;收到 401 时回调(令牌失效)。 */
class AuthInterceptor(
    private val current: () -> Session?,
    private val onUnauthorized: (Session) -> Unit,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val s = current()
        var req = chain.request()
        val route = Route.current.value?.trimEnd('/')
        val u = req.url.toString()
        // 发往服务器原地址,或当前选中的内网直连地址(同一台服务器,证书指纹已校验)的请求才带令牌
        if (s != null && (u.startsWith(s.baseUrl) || (route != null && u.startsWith(route))) && req.header("Authorization") == null) {
            req = req.newBuilder().header("Authorization", "Bearer ${s.token}").build()
        }
        val resp = chain.proceed(req)
        if (resp.code == 401 && s != null && (u.startsWith(s.baseUrl) || (route != null && u.startsWith(route))) && !req.url.encodedPath.endsWith("/auth/login")) onUnauthorized(s)
        return resp
    }
}

/** 客户端标识:服务端的"客户端连接"页面靠它显示 App 版本和机型。形如 LocalBrowse/3.1.0 (Android 17; OPPO OPD2515)。 */
object ClientInfo {
    @Volatile var userAgent: String = "LocalBrowse"
    fun init(ctx: android.content.Context) {
        val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
        val model = (android.os.Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() } + " " + android.os.Build.MODEL.orEmpty()).trim()
        // 请求头只能是可见 ASCII
        userAgent = "LocalBrowse/$ver (Android ${android.os.Build.VERSION.RELEASE}; $model)".filter { it.code in 32..126 }
    }
}

class UserAgentInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", ClientInfo.userAgent).build())
}

/** 记录网络请求:失败 / 慢请求记为警告,其余只在"调试"级别记录。不记录请求头和请求体(含令牌和密码)。 */
class LogInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val what = req.method + " " + req.url.encodedPath + (req.url.encodedQuery?.let { "?" + it.take(80) } ?: "")
        val t0 = System.nanoTime()
        try {
            val resp = chain.proceed(req)
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (resp.code == 504 && ms < 5) com.localtg.AppLog.d("net", "$what 暂停加载(快速滑动时只读本地缓存)")
            else if (!resp.isSuccessful && resp.code != 206) com.localtg.AppLog.w("net", "$what -> ${resp.code} (${ms}ms)")
            else if (ms > 3000) com.localtg.AppLog.w("net", "$what 较慢 -> ${resp.code} (${ms}ms)")
            else com.localtg.AppLog.d("net", "$what -> ${resp.code} (${ms}ms)")
            return resp
        } catch (e: IOException) {
            com.localtg.AppLog.w("net", "$what 失败: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }
}

/** 两个客户端:media = 图片 / 视频 / 播放器(大流量,可能同时很多条);api = 浏览接口(小请求,要求响应快)。见 [buildHttpClients]。 */
class HttpClients(val media: OkHttpClient, val api: OkHttpClient)

/**
 * 网络稳定性(2.4.0):
 *  - 接口和媒体各用一个客户端,**各有自己的连接池和调度器**:以前全部挤在一个客户端里 —— 默认每个主机最多 5 个并发请求,
 *    网络一卡,5 个缩略图请求占满名额,浏览接口(文件夹列表 / 历史 / 保存进度)连发出去的机会都没有;
 *    而且共用一条 HTTP/2 连接,视频缓冲占满链路时,小请求排在后面(日志里同一时刻结束的 9 秒、17 秒的小请求);
 *  - HTTP/2 每 10 秒发一次 PING:连接在系统层面已经死了(切换网络、云转发掉线)但没有收到 RST 时,几十秒读取超时之前就能发现并断开;
 *  - DNS 失败时用上一次解析成功的地址。
 */
fun buildHttpClients(store: SessionStore, local: LocalMedia, connectSec: Int, readSec: Int, onUnauthorized: (Session) -> Unit): HttpClients {
    val base = buildHttpClient(store, local, connectSec, readSec, onUnauthorized)
    val media = base.newBuilder()
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
        .build()
    val api = base.newBuilder()
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 16 })
        .connectionPool(okhttp3.ConnectionPool(4, 2, TimeUnit.MINUTES))
        .callTimeout(40, TimeUnit.SECONDS) // 一次接口请求从头到尾最多 40 秒(含连接、等待响应、读完内容)
        .build()
    return HttpClients(media, api)
}

private fun buildHttpClient(store: SessionStore, local: LocalMedia, connectSec: Int, readSec: Int, onUnauthorized: (Session) -> Unit): OkHttpClient {
    val (sslCtx, trust) = Tls.pinnedContext { store.trustedPins() }
    return OkHttpClient.Builder()
        .dns(FallbackDns())
        .pingInterval(10, TimeUnit.SECONDS)
        // HTTPS:只接受指纹已被用户确认的自签名证书(不校验主机名,服务器 IP 变了也能连);HTTP 地址不受影响
        .sslSocketFactory(sslCtx.socketFactory, trust)
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(connectSec.toLong(), TimeUnit.SECONDS)
        .readTimeout(readSec.toLong(), TimeUnit.SECONDS)
        .addInterceptor(LocalInterceptor(local)) // 本地媒体模式:发往 local.mediahub 的请求在进程内应答,不走网络
        .addInterceptor(AuthInterceptor({ store.session.value }, onUnauthorized))
        .addInterceptor(RouteInterceptor { store.session.value?.baseUrl }) // 在 Auth 之后:令牌按"原地址"判断是否附加,再把主机换成内网直连的
        .addInterceptor(UserAgentInterceptor())
        .addInterceptor(LogInterceptor())
        .build()
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
}

/**
 * 列表接口(群组列表 / 历史)的磁盘缓存:服务端给了 ETag,下次请求带 If-None-Match,没变就 304(没有正文);
 * 同时能先把缓存的上次内容拿出来马上显示(进入群组 / 打开应用时不用等网络)。缓存总量超过 24MB 时删掉最旧的四分之一。
 */
object ApiDiskCache {
    @Volatile private var dir: java.io.File? = null
    private var writes = 0
    class Saved(val etag: String, val body: String)

    fun init(ctx: android.content.Context) { dir = java.io.File(ctx.cacheDir, "api").apply { mkdirs() } }

    private fun file(key: String): java.io.File? {
        val d = dir ?: return null
        val h = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return java.io.File(d, h)
    }

    fun read(key: String): Saved? = runCatching {
        val f = file(key)?.takeIf { it.isFile } ?: return null
        val t = f.readText()
        val i = t.indexOf('\n')
        if (i <= 0) null else Saved(t.substring(0, i), t.substring(i + 1)).also { f.setLastModified(System.currentTimeMillis()) }
    }.getOrNull()

    fun write(key: String, etag: String, body: String) {
        runCatching {
            file(key)?.writeText(etag + "\n" + body)
            if (++writes % 40 == 0) trim()
        }
    }

    private fun trim() {
        val files = dir?.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= 24L * 1024 * 1024) return
        for (f in files.sortedBy { it.lastModified() }.take(files.size / 4 + 1)) { total -= f.length(); f.delete() }
    }
}

class CompatStatus(val ready: Boolean, val state: String = "none", val progress: Double = 0.0, val err: String = "", val why: String = "")

class Api(private val http: OkHttpClient, private val store: SessionStore) {
    private val json = "application/json; charset=utf-8".toMediaType()

    private val base: String get() = store.session.value?.baseUrl ?: error("未登录")

    private suspend inline fun <reified T> call(request: Request): T = AppJson.decodeFromString<T>(callBody(request))

    /**
     * 带磁盘缓存的 GET:缓存里有上次的内容就先交给 onCached(调用方马上显示),再带 If-None-Match 请求;304 = 没变,直接用缓存。
     * 缓存键 = 服务器 + 令牌 + 完整地址(不同账号 / 服务器互不串)。
     */
    private suspend inline fun <reified T> callCached(request: Request, noinline onCached: ((T) -> Unit)? = null): T {
        val s = store.session.value
        val key = (s?.baseUrl ?: "") + "|" + (s?.token?.take(12) ?: "") + "|" + request.url.toString()
        val saved = ApiDiskCache.read(key)
        if (saved != null && onCached != null) runCatching { onCached(AppJson.decodeFromString<T>(saved.body)) }
        val req = if (saved != null) request.newBuilder().header("If-None-Match", saved.etag).build() else request
        val body = callBody(req, saved) { etag, b -> ApiDiskCache.write(key, etag, b) }
        return AppJson.decodeFromString<T>(body)
    }

    /**
     * 发一次接口请求并返回响应内容。网络类失败(断网、DNS、连接超时、连接被掐、切换网络时请求被取消)自动重试:
     *  - 离线时先等网络回来(最多 10 秒),不白白失败;
     *  - GET / PUT / DELETE 是幂等的,最多重试 3 次(间隔 0.7 / 1.4 / 2.1 秒);登录等 POST 不重试;
     *  - 服务端返回了错误码(4xx / 5xx)是确定的结果,不重试。
     */
    private suspend fun callBody(request: Request, saved: ApiDiskCache.Saved? = null, onEtag: ((String, String) -> Unit)? = null): String = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            if (!Net.online) Net.awaitOnline(10_000)
            val gen = Net.gen
            try {
                return@withContext http.newCall(request).await().use { resp ->
                    val body = resp.body.string()
                    if (resp.code == 304 && saved != null) return@use saved.body // 没变:用缓存里的
                    if (!resp.isSuccessful) {
                        val code = Regex("\"code\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: "http.${resp.code}"
                        val msg = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: "HTTP ${resp.code}"
                        throw ApiException(resp.code, code, msg)
                    }
                    resp.header("ETag")?.let { onEtag?.invoke(it, body) }
                    body
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: IOException) {
                val idempotent = request.method == "GET" || request.method == "PUT" || request.method == "DELETE" || request.method == "HEAD"
                val netSwitched = Net.gen != gen // 失败是因为切换网络时请求被取消:不算一次重试机会
                attempt++
                if (!idempotent || (attempt > 3 && !netSwitched) || attempt > 6) throw e
                com.localtg.AppLog.i("net", "${request.method} ${request.url.encodedPath} 失败(${e.javaClass.simpleName}),${attempt * 700}ms 后重试($attempt)")
                kotlinx.coroutines.delay(attempt * 700L)
            }
        }
        @Suppress("UNREACHABLE_CODE") ""
    }

    // ---- 登录前(显式传入地址)----
    suspend fun serverInfo(baseUrl: String): ServerInfo =
        call(Request.Builder().url("$baseUrl/api/v1/server/info").build())

    suspend fun login(baseUrl: String, user: String, pass: String, device: String): LoginResp {
        val body = AppJson.encodeToString(mapOf("username" to user, "password" to pass, "deviceName" to device))
        return call(Request.Builder().url("$baseUrl/api/v1/auth/login").post(body.toRequestBody(json)).build())
    }

    // ---- 登录后 ----
    /** 单个文件夹(群)的信息:标题、数量、封面。 */
    suspend fun dialog(id: String): Dialog = call(Request.Builder().url("$base/api/v1/dialogs/$id").build())

    suspend fun dialogs(group: String = "all", sort: String = "last", cursor: String? = null, limit: Int = 100, onCached: ((DialogsResp) -> Unit)? = null): DialogsResp {
        val url = "$base/api/v1/dialogs".toHttpUrl().newBuilder()
            .addQueryParameter("group", group).addQueryParameter("sort", sort).addQueryParameter("limit", limit.toString())
        cursor?.let { url.addQueryParameter("cursor", it) }
        return callCached(Request.Builder().url(url.build()).build(), onCached)
    }

    /** around 不为空时以该条目为中心取一页(用于恢复上次浏览位置);cursor 带方向,可向前或向后翻页。 */
    suspend fun history(
        dialogId: String, sort: String, dir: String, types: String, cursor: String?, limit: Int, around: String? = null,
        aroundDate: String? = null,
    ): HistoryResp {
        val url = "$base/api/v1/dialogs/$dialogId/history".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort).addQueryParameter("dir", dir)
            .addQueryParameter("types", types).addQueryParameter("limit", limit.toString())
        cursor?.let { url.addQueryParameter("cursor", it) }
        around?.let { url.addQueryParameter("around", it) }
        aroundDate?.let { url.addQueryParameter("aroundDate", it) } // yyyy-MM-dd,仅按时间排序时有效
        return callCached(Request.Builder().url(url.build()).build()) // 服务端 3.2.0 起带 ETag:没变化的页只回 304(旧服务端照常 200)
    }

    /** 全局按文件名搜索(types 如 "photo,video");cursor 是偏移量,可连续翻页。 */
    suspend fun search(q: String, types: String, cursor: String?, limit: Int = 60): HistoryResp {
        val url = "$base/api/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", q).addQueryParameter("types", types).addQueryParameter("limit", limit.toString())
        cursor?.let { url.addQueryParameter("cursor", it) }
        return call(Request.Builder().url(url.build()).build())
    }

    /**
     * 随机取一批(服务端 1.5.0 起有这个接口;roots 要 1.6.0 起;seed 要 1.7.0 起)。
     * 带 seed 时是固定的随机序列:同一个 seed 的第 batch 批永远是同一批(换 seed 才换序列);不带 seed 每次都不同。批与批之间可能重复,调用方自己去重。
     */
    suspend fun random(types: String, limit: Int = 60, roots: String? = null, seed: Long = 0, batch: Int = 0): HistoryResp {
        val url = "$base/api/v1/random".toHttpUrl().newBuilder().addQueryParameter("types", types).addQueryParameter("limit", limit.toString())
        if (!roots.isNullOrEmpty()) url.addQueryParameter("roots", roots) // 只在这些根目录(一个盘符)里随机;不带 = 所有盘符
        if (seed != 0L) url.addQueryParameter("seed", seed.toString()).addQueryParameter("batch", batch.toString())
        return call(Request.Builder().url(url.build()).build())
    }

    /** 让服务端清除所有历史随机序列并重新生成(服务端 1.8.0 起)。 */
    suspend fun randomReset() {
        send(Request.Builder().url("$base/api/v1/random/reset").post("".toRequestBody()).build())
    }

    suspend fun logout() {
        runCatching {
            http.newCall(Request.Builder().url("$base/api/v1/auth/logout").post("".toRequestBody()).build()).await().close()
        }
    }

    // ---- 媒体地址(令牌由拦截器加在请求头里,不放 URL)----
    fun fileUrl(item: Item): String = "$base/api/v1/media/${item.id}/file?v=${item.v}"
    fun posterUrl(item: Item): String = "$base/api/v1/media/${item.id}/poster?v=${item.v}"
    fun fileUrl(id: String): String = "$base/api/v1/media/$id/file"

    /** 服务端转码(内置 ffmpeg 或 Jellyfin 的 HLS)的播放地址:maxBitrate 视频码率(bps,最高 6 Mbps),sid 本次播放的会话标识,codec 输出编码(h264 / h265)。 */
    fun hlsUrl(item: Item, maxBitrate: Int, sid: String, audioIndex: Int? = null, codec: String? = null): String =
        "$base/api/v1/media/${item.id}/hls/master.m3u8?maxBitrate=$maxBitrate&sid=$sid" + (audioIndex?.let { "&audio=$it" } ?: "") + (codec?.let { "&codec=$it" } ?: "")

    // ---- 兼容版本(服务端 3.2.0 起):视频流原样复制、只把音频转成 AAC 装进 MP4,缓存在服务端;之后直放,不用整条转码 ----
    fun compatUrl(item: Item): String = "$base/api/v1/media/${item.id}/compat?v=${item.v}"

    /** 兼容版本做好了没有。用 Range 0-0 探测:做好了是 206 / 200(只取 1 字节),没做好是 202 + 状态 JSON。 */
    suspend fun compatStatus(item: Item): CompatStatus = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(compatUrl(item)).header("Range", "bytes=0-0").build()).await().use { resp ->
                if (resp.code == 200 || resp.code == 206) CompatStatus(true, "ready", 1.0)
                else parseCompat(resp.body.string())
            }
        }.getOrElse { CompatStatus(false, "none") }
    }

    /** 请求服务端在后台制作兼容版本(已有 / 正在做就返回当前状态)。 */
    suspend fun compatPrepare(item: Item): CompatStatus = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$base/api/v1/media/${item.id}/compat/prepare").post("".toRequestBody()).build()).await().use { parseCompat(it.body.string()) }
        }.getOrElse { CompatStatus(false, "none") }
    }

    private fun parseCompat(body: String): CompatStatus {
        fun s(k: String) = Regex("\"$k\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: ""
        val prog = Regex("\"progress\"\\s*:\\s*([0-9.eE+-]+)").find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        return CompatStatus(s("state") == "ready", s("state").ifEmpty { "none" }, prog, s("err"), s("why"))
    }

    // ---- 服务端能力、播放器偏好同步、转码预热(服务端 3.0.0 起)----
    /** 服务端能力;还没取到(或服务端太旧)时是全默认值。 */
    @Volatile var caps: ServerCaps = ServerCaps()
        private set
    private var capsAt = 0L

    /**
     * 取服务端能力(同时也是一次"连接预热":TLS 握手 + HTTP/2 连接建好,之后第一次浏览不用再等握手)。10 分钟内不重复取;force = 切网络后强制重取。
     * 服务端启动时还在探测 QSV(probing)就 1 分钟后再取一次。
     */
    suspend fun refreshCaps(force: Boolean = false): ServerCaps {
        val now = System.currentTimeMillis()
        if (!force && capsAt != 0L && now - capsAt < 10 * 60_000L) return caps
        val info = call<ServerInfo>(Request.Builder().url("$base/api/v1/server/info").build())
        caps = info.caps
        transcodeOk = info.transcode || info.caps.codecs.isNotEmpty()
        capsAt = if (info.caps.probing) now - 9 * 60_000L else now
        com.localtg.AppLog.i("net", "服务端能力:转码编码 ${info.caps.codecs},QSV h264=${info.caps.qsvH264} h265=${info.caps.qsvH265},码率上限 ${info.caps.maxTranscodeBitrate / 1000}kbps,缩略图 WebP=${info.caps.thumbWebp}")
        return caps
    }

    /** 切换服务器 / 退出登录:能力作废。 */
    fun resetCaps() { caps = ServerCaps(); capsAt = 0L; transcodeOk = false }

    /** 服务端能不能转码(没有 ffmpeg / Jellyfin 的服务端不能):自动画质据此决定能不能降档。 */
    @Volatile var transcodeOk: Boolean = false
        private set

    /** 服务器在各个网络上的地址(服务端 3.1.0 起)。 */
    suspend fun addresses(): ServerAddrs = call(Request.Builder().url("$base/api/v1/server/addresses").build())

    @kotlinx.serialization.Serializable
    private class PlayerPrefResp(val json: PlayerPref, val savedAt: Long = 0)

    /** 服务器上记住的播放器偏好;没有记录或出错返回 null。 */
    suspend fun getPlayerPrefRemote(): PlayerPref? = try {
        val r = call<PlayerPrefResp>(Request.Builder().url("$base/api/v1/state/player").build())
        r.json.copy(savedAt = r.savedAt)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e
    } catch (e: Exception) { null }

    suspend fun putPlayerPref(p: PlayerPref) {
        val body = AppJson.encodeToString(p)
        send(Request.Builder().url("$base/api/v1/state/player").put(body.toRequestBody(json)).build())
    }

    /**
     * 让服务端提前把另一个视频的前几段转好(切过去时第一段已经在磁盘上)。sid 要和之后真正播放时用的一致。
     * 失败不影响任何事(只是少了一次预热)。
     */
    suspend fun hlsPrepare(item: Item, maxBitrate: Int, sid: String, codec: String?) {
        runCatching {
            withContext(Dispatchers.IO) {
                val url = "$base/api/v1/media/${item.id}/hls/prepare?maxBitrate=$maxBitrate&sid=$sid" + (codec?.let { "&codec=$it" } ?: "")
                http.newCall(Request.Builder().url(url).post("".toRequestBody()).build()).await().close()
            }
        }
    }

    // ---- 浏览记录 / 播放进度同步(存在服务器上,换设备打开回到上次的位置)----
    @kotlinx.serialization.Serializable
    private class ViewResp(val json: DialogView, val savedAt: Long = 0)

    @kotlinx.serialization.Serializable
    private class PlayResp(val posMs: Long = 0, val savedAt: Long = 0)

    private suspend fun send(req: Request) {
        withContext(Dispatchers.IO) {
            http.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) throw ApiException(resp.code, "http.${resp.code}", "HTTP ${resp.code}")
            }
        }
    }

    /** 服务器上的记录 + 它最后更新的时间(毫秒时间戳,谁最后写入就是谁的)。 */
    class RemoteView(val view: DialogView, val savedAt: Long)
    class RemotePlay(val posMs: Long, val savedAt: Long)

    /**
     * 服务器不认识同步接口(服务端版本太旧,路由不存在返回的是普通 404,而"没有记录"返回的 404 带 code=notfound):
     * 界面据此提示一次"请更新服务端",而不是悄悄失败。任何一次同步成功就清除。
     */
    val syncUnsupported = kotlinx.coroutines.flow.MutableStateFlow(false)

    private fun noteSync(e: Throwable?) {
        if (e == null) syncUnsupported.value = false
        else if (e is ApiException && e.http == 404 && e.code != "notfound") {
            if (!syncUnsupported.value) com.localtg.AppLog.w("sync", "服务器不支持浏览记录 / 播放进度同步(版本太旧?):${e.message}")
            syncUnsupported.value = true
        } else com.localtg.AppLog.d("sync", "同步失败:${e.javaClass.simpleName} ${e.message}")
    }

    /** 服务器上这个群的浏览记录;没有记录或出错返回 null。 */
    suspend fun getDialogViewRemote(dialogId: String): RemoteView? = try {
        val r = call<ViewResp>(Request.Builder().url("$base/api/v1/dialogs/$dialogId/view").build())
        noteSync(null)
        RemoteView(r.json, r.savedAt)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e
    } catch (e: Exception) { noteSync(e); null }

    suspend fun putDialogView(dialogId: String, v: DialogView, savedAt: Long = System.currentTimeMillis()) {
        val body = AppJson.encodeToString(v.copy(savedAt = savedAt))
        try {
            send(Request.Builder().url("$base/api/v1/dialogs/$dialogId/view").put(body.toRequestBody(json)).build())
            noteSync(null)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { noteSync(e); throw e }
    }

    /** 服务器上这个视频的播放进度(毫秒,0 = 看完 / 重新开始)和更新时间;没有记录或出错返回 null。 */
    suspend fun getPlaybackRemote(mediaId: String): RemotePlay? = try {
        val r = call<PlayResp>(Request.Builder().url("$base/api/v1/media/$mediaId/playback").build())
        noteSync(null)
        RemotePlay(r.posMs, r.savedAt)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e
    } catch (e: Exception) { noteSync(e); null }

    suspend fun putPlayback(mediaId: String, posMs: Long, savedAt: Long = System.currentTimeMillis()) {
        val body = AppJson.encodeToString(mapOf("posMs" to posMs, "savedAt" to savedAt))
        try {
            send(Request.Builder().url("$base/api/v1/media/$mediaId/playback").put(body.toRequestBody(json)).build())
            noteSync(null)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { noteSync(e); throw e }
    }

    suspend fun clearSyncedViews() = send(Request.Builder().url("$base/api/v1/state/views").delete().build())

    suspend fun clearSyncedPlayback() = send(Request.Builder().url("$base/api/v1/state/playback").delete().build())

    /** 退出播放时通知服务端结束这个会话的转码,释放 CPU / 核显。 */
    suspend fun stopHls(item: Item, sid: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url("$base/api/v1/media/${item.id}/hls?sid=$sid").delete().build()).await().close()
            }
        }
    }

    /** 为 true 时 HEIC / HEIF / AVIF 也走服务端转换(设置里的"HEIC 显示方式")。 */
    @Volatile var serverRenderHeic: Boolean = true

    /** 显示图片用的地址:手机能直接显示的用原文件;其它格式由服务端转成 JPEG(w = 需要的长边像素,服务端取最近的一档)。 */
    fun imageUrl(item: Item, w: Int): String =
        // 本地媒体:动图和全屏查看直接读原文件(可无损放大),其余由本机缩小后返回
        if (store.local.value) { if (item.ext == "gif" || w > 2000) fileUrl(item) else "$base/api/v1/media/${item.id}/render?w=${w.coerceAtMost(4096)}&v=${item.v}" }
        else if (needsServerRender(item.ext, serverRenderHeic)) "$base/api/v1/media/${item.id}/render?w=$w&v=${item.v}"
        // 小图(网格 / 聊天流 / 预加载):服务端生成并缓存好的 WebP 缩略图,几十 KB;不用为了一个小格子下载几 MB 的原图
        else if (thumbEligible(item, w)) "$base/api/v1/media/${item.id}/render?w=$w&fmt=webp&v=${item.v}"
        else fileUrl(item)

    /** 是否用服务端的 WebP 缩略图:服务端支持、静态位图、原图明显比要显示的大。出错时调用方退回 [fileUrl]。 */
    private fun thumbEligible(item: Item, w: Int): Boolean =
        caps.thumbWebp && item.type == "photo" && item.ext.lowercase() in THUMB_EXT && w in 1..1440 && item.size > 120_000 && (item.w ?: 0) > w

    /** 这个地址是不是缩略图地址(失败时要退回原图)。 */
    fun isThumbUrl(url: String): Boolean = url.contains("fmt=webp")
    fun posterUrl(id: String): String = "$base/api/v1/media/$id/poster"
}

/** 把用户输入的地址规范成 http(s)://host:port。未写协议补 https://(加密传输),未写端口补 8480。 */
private val THUMB_EXT = setOf("jpg", "jpeg", "jpe", "jfif", "png", "webp", "bmp")

fun normalizeAddress(input: String): String {
    var s = input.trim().trimEnd('/')
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
    val hostPart = s.substringAfter("://").substringBefore('/')
    if (!hostPart.contains(':') || hostPart.endsWith(']')) s = s.replaceFirst(hostPart, "$hostPart:8480")
    return s
}

fun friendlyError(e: Throwable): String = when (e) {
    is ApiException -> if (e.http == 401) "用户名或密码错误" else e.message ?: "服务器错误"
    is java.net.UnknownHostException -> "找不到服务器,请检查地址"
    is java.net.ConnectException, is java.net.SocketTimeoutException -> "无法连接到服务器,请检查地址、端口和网络"
    is javax.net.ssl.SSLHandshakeException, is java.security.cert.CertificateException ->
        if (e.message?.contains("指纹") == true || e.cause?.message?.contains("指纹") == true)
            "服务器证书与之前信任的不一致(服务端重新生成了证书,或连到了别的设备)。请退出登录后重新连接,并核对证书指纹"
        else "无法建立加密连接:${e.message}。如果服务端关闭了加密传输,请在地址前写 http://"
    is javax.net.ssl.SSLException -> "无法建立加密连接(服务器可能不是 HTTPS):${e.message}"
    is IOException -> "网络错误:${e.message}"
    is kotlinx.serialization.SerializationException -> "服务器返回的数据不是 MediaHub 的格式"
    else -> e.message ?: e.toString()
}
