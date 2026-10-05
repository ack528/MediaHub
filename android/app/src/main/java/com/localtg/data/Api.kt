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
    private val onUnauthorized: () -> Unit,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val s = current()
        var req = chain.request()
        if (s != null && req.url.toString().startsWith(s.baseUrl) && req.header("Authorization") == null) {
            req = req.newBuilder().header("Authorization", "Bearer ${s.token}").build()
        }
        val resp = chain.proceed(req)
        if (resp.code == 401 && s != null && !req.url.encodedPath.endsWith("/auth/login")) onUnauthorized()
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
            if (!resp.isSuccessful && resp.code != 206) com.localtg.AppLog.w("net", "$what -> ${resp.code} (${ms}ms)")
            else if (ms > 3000) com.localtg.AppLog.w("net", "$what 较慢 -> ${resp.code} (${ms}ms)")
            else com.localtg.AppLog.d("net", "$what -> ${resp.code} (${ms}ms)")
            return resp
        } catch (e: IOException) {
            com.localtg.AppLog.w("net", "$what 失败: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }
}

fun buildHttpClient(store: SessionStore, connectSec: Int, readSec: Int, onUnauthorized: () -> Unit): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(connectSec.toLong(), TimeUnit.SECONDS)
        .readTimeout(readSec.toLong(), TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor({ store.session.value }, onUnauthorized))
        .addInterceptor(LogInterceptor())
        .build()

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
    ): HistoryResp {
        val url = "$base/api/v1/dialogs/$dialogId/history".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort).addQueryParameter("dir", dir)
            .addQueryParameter("types", types).addQueryParameter("limit", limit.toString())
        cursor?.let { url.addQueryParameter("cursor", it) }
        around?.let { url.addQueryParameter("around", it) }
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
    fun posterUrl(id: String): String = "$base/api/v1/media/$id/poster"
}

/** 把用户输入的地址规范成 http(s)://host:port。未写协议补 http://,未写端口补 8480。 */
fun normalizeAddress(input: String): String {
    var s = input.trim().trimEnd('/')
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
    val hostPart = s.substringAfter("://").substringBefore('/')
    if (!hostPart.contains(':') || hostPart.endsWith(']')) s = s.replaceFirst(hostPart, "$hostPart:8480")
    return s
}

fun friendlyError(e: Throwable): String = when (e) {
    is ApiException -> if (e.http == 401) "用户名或密码错误" else e.message ?: "服务器错误"
    is java.net.UnknownHostException -> "找不到服务器,请检查地址"
    is java.net.ConnectException, is java.net.SocketTimeoutException -> "无法连接到服务器,请检查地址、端口和网络"
    is IOException -> "网络错误:${e.message}"
    is kotlinx.serialization.SerializationException -> "服务器返回的数据不是 MediaHub 的格式"
    else -> e.message ?: e.toString()
}
