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

fun buildHttpClient(store: SessionStore, local: LocalMedia, connectSec: Int, readSec: Int, onUnauthorized: (Session) -> Unit): OkHttpClient {
    val (sslCtx, trust) = Tls.pinnedContext { store.trustedPins() }
    return OkHttpClient.Builder()
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

    private suspend inline fun <reified T> call(request: Request): T = withContext(Dispatchers.IO) {
        http.newCall(request).await().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) {
                val code = Regex("\"code\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: "http.${resp.code}"
                val msg = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: "HTTP ${resp.code}"
                throw ApiException(resp.code, code, msg)
            }
            AppJson.decodeFromString<T>(body)
        }
    }

    // ---- 登录前(显式传入地址)----
    suspend fun serverInfo(baseUrl: String): ServerInfo =
        call(Request.Builder().url("$baseUrl/api/v1/server/info").build())

    suspend fun login(baseUrl: String, user: String, pass: String, device: String): LoginResp {
        val body = AppJson.encodeToString(mapOf("username" to user, "password" to pass, "deviceName" to device))
        return call(Request.Builder().url("$baseUrl/api/v1/auth/login").post(body.toRequestBody(json)).build())
    }

    // ---- 登录后 ----
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

    /** 服务器上这个群的浏览记录;没有记录(404)或出错返回 null。 */
    suspend fun getDialogView(dialogId: String): DialogView? = runCatching {
        call<ViewResp>(Request.Builder().url("$base/api/v1/dialogs/$dialogId/view").build()).json
    }.getOrNull()

    suspend fun putDialogView(dialogId: String, v: DialogView) {
        val body = AppJson.encodeToString(v.copy(savedAt = System.currentTimeMillis()))
        send(Request.Builder().url("$base/api/v1/dialogs/$dialogId/view").put(body.toRequestBody(json)).build())
    }

    /** 服务器上这个视频的播放进度(毫秒,0 = 看完 / 重新开始);没有记录或出错返回 null。 */
    suspend fun getPlayback(mediaId: String): Long? = runCatching {
        call<PlayResp>(Request.Builder().url("$base/api/v1/media/$mediaId/playback").build()).posMs
    }.getOrNull()

    suspend fun putPlayback(mediaId: String, posMs: Long) {
        val body = AppJson.encodeToString(mapOf("posMs" to posMs, "savedAt" to System.currentTimeMillis()))
        send(Request.Builder().url("$base/api/v1/media/$mediaId/playback").put(body.toRequestBody(json)).build())
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
