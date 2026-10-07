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
        if (s != null && req.url.toString().startsWith(s.baseUrl) && req.header("Authorization") == null) {
            req = req.newBuilder().header("Authorization", "Bearer ${s.token}").build()
        }
        val resp = chain.proceed(req)
        if (resp.code == 401 && s != null && req.url.toString().startsWith(s.baseUrl) && !req.url.encodedPath.endsWith("/auth/login")) onUnauthorized(s)
        return resp
    }
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

class Api(private val http: OkHttpClient, private val store: SessionStore) {
    private val json = "application/json; charset=utf-8".toMediaType()

    private val base: String get() = store.session.value?.baseUrl ?: error("未登录")

    private suspend inline fun <reified T> call(request: Request): T = AppJson.decodeFromString<T>(callBody(request))

    /**
     * 发一次接口请求并返回响应内容。网络类失败(断网、DNS、连接超时、连接被掐、切换网络时请求被取消)自动重试:
     *  - 离线时先等网络回来(最多 10 秒),不白白失败;
     *  - GET / PUT / DELETE 是幂等的,最多重试 3 次(间隔 0.7 / 1.4 / 2.1 秒);登录等 POST 不重试;
     *  - 服务端返回了错误码(4xx / 5xx)是确定的结果,不重试。
     */
    private suspend fun callBody(request: Request): String = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            if (!Net.online) Net.awaitOnline(10_000)
            val gen = Net.gen
            try {
                return@withContext http.newCall(request).await().use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) {
                        val code = Regex("\"code\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: "http.${resp.code}"
                        val msg = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: "HTTP ${resp.code}"
                        throw ApiException(resp.code, code, msg)
                    }
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

    suspend fun dialogs(group: String = "all", sort: String = "last", cursor: String? = null, limit: Int = 100): DialogsResp {
        val url = "$base/api/v1/dialogs".toHttpUrl().newBuilder()
            .addQueryParameter("group", group).addQueryParameter("sort", sort).addQueryParameter("limit", limit.toString())
        cursor?.let { url.addQueryParameter("cursor", it) }
        return call(Request.Builder().url(url.build()).build())
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
        return call(Request.Builder().url(url.build()).build())
    }

    /** 全局按文件名搜索(types 如 "photo,video");cursor 是偏移量,可连续翻页。 */
    suspend fun search(q: String, types: String, cursor: String?, limit: Int = 60): HistoryResp {
        val url = "$base/api/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", q).addQueryParameter("types", types).addQueryParameter("limit", limit.toString())
        cursor?.let { url.addQueryParameter("cursor", it) }
        return call(Request.Builder().url(url.build()).build())
    }

    /** 随机取一批(服务端 1.5.0 起有这个接口)。每次调用结果都不同,可能和之前取过的重复,调用方自己去重。 */
    suspend fun random(types: String, limit: Int = 60): HistoryResp {
        val url = "$base/api/v1/random".toHttpUrl().newBuilder().addQueryParameter("types", types).addQueryParameter("limit", limit.toString())
        return call(Request.Builder().url(url.build()).build())
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

    /** 服务端转码(内置 ffmpeg 或 Jellyfin 的 HLS)的播放地址:maxHeight 目标高度,maxBitrate 视频码率(bps),sid 本次播放的会话标识。 */
    fun hlsUrl(item: Item, maxBitrate: Int, sid: String, audioIndex: Int? = null): String =
        "$base/api/v1/media/${item.id}/hls/master.m3u8?maxBitrate=$maxBitrate&sid=$sid" + (audioIndex?.let { "&audio=$it" } ?: "")

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
        else if (needsServerRender(item.ext, serverRenderHeic)) "$base/api/v1/media/${item.id}/render?w=$w&v=${item.v}" else fileUrl(item)
    fun posterUrl(id: String): String = "$base/api/v1/media/$id/poster"
}

/** 把用户输入的地址规范成 http(s)://host:port。未写协议补 https://(加密传输),未写端口补 8480。 */
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
