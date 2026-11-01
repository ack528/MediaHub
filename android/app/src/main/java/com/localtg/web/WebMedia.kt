package com.localtg.web

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.localtg.AppContainer
import coil3.asImage
import com.localtg.AppLog
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 网页界面的两类请求都在进程内应答(不开端口、不走 file://):
 *  - `https://appassets.androidplatform.net/` 下的页面、脚本、样式:读安装包 assets/web/;
 *  - `/srv/...` 媒体(缩略图 / 封面 / 原图):转发给当前服务器(或本地媒体),走原来的媒体 OkHttp 客户端 ——
 *    令牌加在请求头里、证书指纹校验、内网直连、本地媒体模式都和以前一样,网页拿不到令牌。
 *    带版本号(v=)的地址内容不会变,强制按长期缓存存进磁盘,再看不用重新下载。
 */
class WebMedia(private val ctx: Context, private val c: AppContainer) {
    private val client: OkHttpClient = c.http.newBuilder()
        .cache(Cache(dir(ctx), c.settings.value.imageDiskCacheMb.toLong() * 1024 * 1024))
        .addNetworkInterceptor { chain ->
            val resp = chain.proceed(chain.request())
            if (resp.isSuccessful && chain.request().url.queryParameter("v") != null)
                resp.newBuilder().header("Cache-Control", "public, max-age=31536000, immutable").removeHeader("Pragma").removeHeader("Expires").build()
            else resp
        }
        .build()

    fun handle(req: WebResourceRequest): WebResourceResponse? {
        val u = req.url
        if (u.host != HOST) return null
        val path = u.encodedPath ?: "/"
        return if (path.startsWith("/srv/")) proxy(req, path.removePrefix("/srv") + (u.encodedQuery?.let { "?$it" } ?: "")) else asset(path)
    }

    private fun asset(path: String): WebResourceResponse {
        val rel = path.trimStart('/').ifEmpty { "index.html" }
        val name = if (rel.contains('.')) rel else "index.html" // 单页应用:没有扩展名的地址都回到 index.html
        return try {
            val stream = ctx.assets.open("web/$name")
            WebResourceResponse(mime(name), if (mime(name).startsWith("text") || name.endsWith(".js")) "utf-8" else null, 200, "OK",
                mapOf("Cache-Control" to if (name == "index.html") "no-cache" else "max-age=31536000"), stream)
        } catch (e: java.io.IOException) {
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    /**
     * 把网页列表里显示的那张缩略图([src] = 网页里的图片地址)解码放进原生图片的内存缓存(键和以前的列表一样):
     * 查看器展开动画的第一帧就有缩略图垫底,不是一片黑。网页刚显示过,一般直接命中磁盘缓存。
     */
    suspend fun warmThumb(item: com.localtg.data.Item, src: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val loader = coil3.SingletonImageLoader.get(ctx)
        val key = coil3.memory.MemoryCache.Key(com.localtg.ui.thumbKey(item))
        val cache = loader.memoryCache ?: return@withContext
        if (cache[key] != null) return@withContext
        val path = android.net.Uri.parse(src).let { u -> if (u.host == HOST) (u.encodedPath ?: "") + (u.encodedQuery?.let { "?$it" } ?: "") else return@withContext }
        if (!path.startsWith("/srv/")) return@withContext
        val base = c.session.session.value?.baseUrl ?: return@withContext
        runCatching {
            client.newCall(Request.Builder().url(base.trimEnd('/') + path.removePrefix("/srv")).build()).execute().use { r ->
                if (!r.isSuccessful) return@use
                val bmp = android.graphics.BitmapFactory.decodeStream(r.body.byteStream()) ?: return@use
                cache[key] = coil3.memory.MemoryCache.Value(bmp.asImage())
            }
        }.onFailure { AppLog.w("web", "缩略图预热失败 ${item.id}: ${it.message}") }
    }

    private fun proxy(req: WebResourceRequest, pathAndQuery: String): WebResourceResponse {
        val base = c.session.session.value?.baseUrl
            ?: return WebResourceResponse("text/plain", "utf-8", 401, "Unauthorized", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        val b = Request.Builder().url(base.trimEnd('/') + pathAndQuery)
        req.requestHeaders["Range"]?.let { b.header("Range", it) }
        return try {
            val r = client.newCall(b.build()).execute()
            val type = r.body.contentType()
            val headers = buildMap {
                put("Access-Control-Allow-Origin", "*")
                r.header("Content-Length")?.let { put("Content-Length", it) }
                r.header("Content-Range")?.let { put("Content-Range", it) }
                r.header("Accept-Ranges")?.let { put("Accept-Ranges", it) }
            }
            val code = r.code.takeIf { it !in 300..399 } ?: 502
            WebResourceResponse(
                type?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream", type?.charset()?.name(),
                code, r.message.ifEmpty { if (code in 200..299) "OK" else "Error" }, headers, r.body.byteStream(),
            )
        } catch (e: Exception) {
            AppLog.w("web", "媒体请求失败 $pathAndQuery: ${e.javaClass.simpleName} ${e.message}")
            WebResourceResponse("text/plain", "utf-8", 502, "Bad Gateway", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$HOST/"

        private fun dir(ctx: Context) = File(ctx.cacheDir, "web-media")
        fun cacheSize(ctx: Context): Long = dir(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        fun clear(ctx: Context) { dir(ctx).listFiles()?.forEach { it.deleteRecursively() } }

        fun mime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
            "html" -> "text/html"
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "woff2" -> "font/woff2"
            "ico" -> "image/x-icon"
            else -> "application/octet-stream"
        }
    }
}
