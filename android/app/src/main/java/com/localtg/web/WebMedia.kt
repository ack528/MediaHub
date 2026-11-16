package com.localtg.web

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.localtg.AppContainer
import coil3.asImage
import com.localtg.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * 网页界面的两类请求都在进程内应答(不开端口、不走 file://):
 *  - `https://appassets.androidplatform.net/` 下的页面、脚本、样式:读安装包 assets/web/;
 *  - `/srv/...` 媒体(缩略图 / 封面 / 原图):转发给当前服务器(或本地媒体),走原来的媒体 OkHttp 客户端 ——
 *    令牌加在请求头里、证书指纹校验、内网直连、本地媒体模式都和以前一样,网页拿不到令牌。
 *
 * 带版本号(v=)的地址内容不会变,存进自己的磁盘缓存([readCached] / [store]),再看不用重新下载。
 * 缓存键是"服务器原地址 + 路径 + 查询",**不含内网直连改写后的主机**:以前用的是 OkHttp 自带的缓存,键是改写之后的地址 ——
 * 启动时内网线路还没探测完,请求走原地址,探测完后走内网地址,两边各存各的,下次启动一开始用原地址就全部没命中,
 * 群头像和上次停留位置的缩略图每次启动都要重新下载。
 */
class WebMedia(private val ctx: Context, private val c: AppContainer) {
    private val client: OkHttpClient get() = shared(ctx, c)

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
            val pq = path.removePrefix("/srv")
            val bytes = readCachedBytes(base, pq) ?: client.newCall(Request.Builder().url(base.trimEnd('/') + pq).build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                r.body.bytes().also { store(base, pq, r.body.contentType()?.let { "${it.type}/${it.subtype}" }, it) }
            } ?: return@runCatching
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching
            cache[key] = coil3.memory.MemoryCache.Value(bmp.asImage())
        }.onFailure { AppLog.w("web", "缩略图预热失败 ${item.id}: ${it.message}") }
    }

    private fun proxy(req: WebResourceRequest, pathAndQuery: String): WebResourceResponse {
        val base = c.session.session.value?.baseUrl
            ?: return WebResourceResponse("text/plain", "utf-8", 401, "Unauthorized", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        val range = req.requestHeaders["Range"]
        val cacheable = req.method == "GET" && range == null && VERSIONED.containsMatchIn(pathAndQuery)
        if (cacheable) readCached(base, pathAndQuery)?.let { return it }
        val b = Request.Builder().url(base.trimEnd('/') + pathAndQuery)
        range?.let { b.header("Range", it) }
        return try {
            val r = client.newCall(b.build()).execute()
            val type = r.body.contentType()
            val code = r.code.takeIf { it !in 300..399 } ?: 502
            val mimeType = type?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream"
            // 内容不会变的小文件(缩略图 / 封面):读完存进磁盘缓存;大文件(原图等)和分段请求照旧流式转发
            val len = r.body.contentLength()
            if (cacheable && r.code == 200 && len in 1..MAX_ENTRY) {
                val bytes = r.body.use { it.bytes() }
                store(base, pathAndQuery, mimeType, bytes)
                return WebResourceResponse(mimeType, type?.charset()?.name(), 200, "OK", mapOf("Access-Control-Allow-Origin" to "*", "Content-Length" to bytes.size.toString()), ByteArrayInputStream(bytes))
            }
            val headers = buildMap {
                put("Access-Control-Allow-Origin", "*")
                r.header("Content-Length")?.let { put("Content-Length", it) }
                r.header("Content-Range")?.let { put("Content-Range", it) }
                r.header("Accept-Ranges")?.let { put("Accept-Ranges", it) }
            }
            WebResourceResponse(mimeType, type?.charset()?.name(),
                code, r.message.ifEmpty { if (code in 200..299) "OK" else "Error" }, headers, r.body.byteStream())
        } catch (e: Exception) {
            AppLog.w("web", "媒体请求失败 $pathAndQuery: ${e.javaClass.simpleName} ${e.message}")
            WebResourceResponse("text/plain", "utf-8", 502, "Bad Gateway", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    // ---------------------------------------------------------------- 磁盘缓存

    private fun file(base: String, pathAndQuery: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest((base.trimEnd('/') + "|" + pathAndQuery).toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir(ctx), h)
    }

    /** 缓存里有就直接应答(顺带刷新访问时间,淘汰时按最近使用);没有返回 null。 */
    private fun readCached(base: String, pathAndQuery: String): WebResourceResponse? = runCatching {
        val f = file(base, pathAndQuery)
        if (!f.isFile) return null
        f.setLastModified(System.currentTimeMillis())
        val input = DataInputStream(FileInputStream(f).buffered())
        val mime = input.readUTF()
        WebResourceResponse(mime, null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*", "Content-Length" to (f.length() - 2 - mime.toByteArray().size).toString()), input)
    }.getOrNull()

    private fun readCachedBytes(base: String, pathAndQuery: String): ByteArray? = runCatching {
        val f = file(base, pathAndQuery)
        if (!f.isFile) return null
        f.setLastModified(System.currentTimeMillis())
        DataInputStream(FileInputStream(f).buffered()).use { it.readUTF(); it.readBytes() }
    }.getOrNull()

    private fun store(base: String, pathAndQuery: String, mime: String?, bytes: ByteArray) {
        if (!VERSIONED.containsMatchIn(pathAndQuery) || bytes.isEmpty() || bytes.size > MAX_ENTRY) return
        runCatching {
            val f = file(base, pathAndQuery)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { it.writeUTF(mime ?: "application/octet-stream"); it.write(bytes) }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            com.localtg.data.CacheBudget.record(com.localtg.data.CacheBudget.Kind.Thumb, bytes.size.toLong()) // 走到网络的缩略图记作"缩略图"的需求
            if (writes.incrementAndGet() % 40 == 0) trimAsync(ctx)
        }.onFailure { AppLog.w("web", "缩略图写缓存失败: ${it.message}") }
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$HOST/"

        /** 带版本号(?v= / &v=)的地址内容不会变。 */
        private val VERSIONED = Regex("[?&]v=")
        private const val MAX_ENTRY = 8L * 1024 * 1024
        private val writes = java.util.concurrent.atomic.AtomicInteger()

        private fun dir(ctx: Context) = File(ctx.cacheDir, "web-thumbs")

        /** 超过分配给"缩略图"的额度时,按最近使用时间删掉最旧的,直到降到额度的 85%。 */
        private fun trimAsync(ctx: Context) {
            Thread({
                runCatching {
                    val files = dir(ctx).listFiles { f -> f.isFile && !f.name.endsWith(".tmp") } ?: return@runCatching
                    val quota = com.localtg.data.CacheBudget.current[com.localtg.data.CacheBudget.Kind.Thumb]
                    var total = files.sumOf { it.length() }
                    if (total <= quota) return@runCatching
                    for (f in files.sortedBy { it.lastModified() }) {
                        if (total <= quota * 85 / 100) break
                        total -= f.length(); f.delete()
                    }
                }
            }, "thumb-trim").apply { priority = Thread.MIN_PRIORITY }.start()
        }

        /** 全应用共用一个客户端(没有 OkHttp 缓存:缩略图缓存见上面的磁盘缓存)。 */
        @Volatile private var client: OkHttpClient? = null
        @Synchronized
        fun shared(ctx: Context, c: AppContainer): OkHttpClient = client ?: c.http.newBuilder().build().also {
            client = it
            // 旧版本用 OkHttp 缓存存在 web-media 里:不再用了,删掉腾出空间
            Thread({ runCatching { File(ctx.applicationContext.cacheDir, "web-media").deleteRecursively() } }, "thumb-cleanup").start()
        }
        fun cacheSize(ctx: Context): Long = dir(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        fun clear(ctx: Context) {
            dir(ctx).listFiles()?.forEach { it.deleteRecursively() }
        }

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
