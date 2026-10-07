package com.localtg.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import com.localtg.AppLog
import kotlinx.serialization.encodeToString
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import okio.source
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 本地媒体模式:手机自己的照片和视频,当作一个"服务器"(见 [LocalInterceptor])。 */
object LocalMode {
    const val HOST = "local.mediahub"
    const val BASE = "http://local.mediahub"
}

/**
 * 读取本机 MediaStore 里的图片和视频,一个文件夹(MediaStore 的 bucket)= 一个群组。
 * 不改动界面代码:本地数据整个伪装成服务端的 HTTP 接口,由 [LocalInterceptor] 在进程内应答。
 */
class LocalMedia(private val ctx: Context) {
    class LItem(
        val id: Long, val uri: Uri, val video: Boolean, val name: String, val ext: String, val mime: String, val size: Long,
        val w: Int, val h: Int, val takenMs: Long, val modMs: Long, val addedMs: Long, val durMs: Long,
        val bucketId: Long, val bucket: String, val rel: String,
    ) {
        val type: String get() = if (video) "video" else if (ext == "gif") "gif" else "photo"
    }

    @Volatile private var all: List<LItem> = emptyList()
    @Volatile private var byId: Map<Long, LItem> = emptyMap()
    @Volatile private var loaded = false
    private val sortCache = HashMap<String, List<LItem>>()

    @Synchronized
    fun reload() {
        val list = ArrayList<LItem>()
        runCatching { list += query(false) }.onFailure { AppLog.w("local", "读取本机图片失败", it) }
        runCatching { list += query(true) }.onFailure { AppLog.w("local", "读取本机视频失败", it) }
        all = list
        byId = list.associateBy { it.id }
        sortCache.clear()
        loaded = true
        AppLog.i("local", "本机媒体 ${list.size} 个,${list.map { it.bucketId }.distinct().size} 个文件夹")
    }

    @Synchronized
    private fun ensure() { if (!loaded) reload() }

    fun item(id: Long): LItem? { ensure(); return byId[id] }

    private fun query(video: Boolean): List<LItem> {
        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = mutableListOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            if (video) MediaStore.Video.VideoColumns.DATE_TAKEN else MediaStore.Images.ImageColumns.DATE_TAKEN,
            if (video) MediaStore.Video.VideoColumns.BUCKET_ID else MediaStore.Images.ImageColumns.BUCKET_ID,
            if (video) MediaStore.Video.VideoColumns.BUCKET_DISPLAY_NAME else MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
            if (video) MediaStore.Video.VideoColumns.DURATION else MediaStore.Images.ImageColumns.ORIENTATION,
            if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA,
        )
        val out = ArrayList<LItem>()
        ctx.contentResolver.query(base, proj.toTypedArray(), null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val rawId = cur.getLong(0)
                val name = cur.getString(1) ?: continue
                val size = cur.getLong(2)
                val mod = cur.getLong(4) * 1000
                val added = cur.getLong(5) * 1000
                var w = cur.getInt(6); var h = cur.getInt(7)
                val taken = cur.getLong(8).takeIf { it > 0 } ?: mod
                val bid = cur.getLong(9)
                val bname = cur.getString(10)?.takeIf { it.isNotBlank() } ?: "其他"
                val extra = cur.getLong(11)
                val rel = cur.getString(12).orEmpty().let { p ->
                    if (Build.VERSION.SDK_INT >= 29) p else p.substringBeforeLast('/', "").removePrefix("/storage/emulated/0/")
                }
                if (!video && extra % 180L != 0L) { val t = w; w = h; h = t } // EXIF 旋转 90 / 270:宽高对调,界面按显示方向用
                val ext = name.substringAfterLast('.', "").lowercase()
                // 视频的 id 和图片的 id 是两套编号,加一个大偏移合成全局唯一的 id
                out += LItem(
                    id = if (video) rawId + VIDEO_OFFSET else rawId,
                    uri = ContentUris.withAppendedId(base, rawId), video = video, name = name, ext = ext,
                    mime = cur.getString(3).orEmpty().ifEmpty { if (video) "video/*" else "image/*" }, size = size,
                    w = w, h = h, takenMs = taken, modMs = mod, addedMs = added, durMs = if (video) extra else 0L,
                    bucketId = bid, bucket = bname, rel = rel,
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------ 接口数据

    private fun iso(ms: Long): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toOffsetDateTime())

    private fun toItem(i: LItem, dialogId: String) = Item(
        id = i.id.toString(), dialogId = dialogId, name = i.name, ext = i.ext, type = i.type, mime = i.mime, size = i.size,
        w = i.w.takeIf { it > 0 }, h = i.h.takeIf { it > 0 }, takenAt = iso(i.takenMs), modifiedAt = iso(i.modMs), createdAt = iso(i.addedMs),
        durationMs = if (i.video) i.durMs else null, v = i.modMs.toString(),
        video = if (i.video) VideoInfo(container = i.ext) else null,
    )

    /** 文件夹列表:按"最新一个文件的时间"从新到旧(没有别的排序方式,服务端的 sort 参数忽略)。 */
    fun dialogs(): DialogsResp {
        reload() // 下拉刷新 / 进入列表时重新读取,新拍的照片马上出现
        val groups = all.groupBy { it.bucketId }.values.map { g ->
            val sorted = g.sortedWith(compareByDescending<LItem> { it.takenMs }.thenByDescending { it.id })
            val last = sorted.first()
            val did = last.bucketId.toString()
            val rel = last.rel.trim('/').replace('/', '\\')
            Dialog(
                id = did, rootId = "local", title = last.bucket,
                pathDisplay = "手机存储" + if (rel.isNotEmpty()) "\\$rel" else "",
                counts = Counts(photo = g.count { it.type == "photo" }, video = g.count { it.video }, gif = g.count { it.type == "gif" }),
                recursiveCount = g.size,
                last = Last(id = last.id.toString(), type = last.type, name = last.name, takenAt = iso(last.takenMs), w = last.w.takeIf { it > 0 }, h = last.h.takeIf { it > 0 }),
                cover = sorted.take(4).map { it.id.toString() },
                version = "${g.size}-${last.takenMs}",
            ) to last.takenMs
        }.sortedByDescending { it.second }.map { it.first }
        return DialogsResp(groups)
    }

    private fun natKey(s: String): List<Any> {
        val out = ArrayList<Any>()
        var i = 0
        while (i < s.length) {
            if (s[i].isDigit()) {
                var j = i; while (j < s.length && s[j].isDigit()) j++
                out += s.substring(i, j).trimStart('0').padStart(20, '0')
                i = j
            } else {
                var j = i; while (j < s.length && !s[j].isDigit()) j++
                out += s.substring(i, j).lowercase()
                i = j
            }
        }
        return out
    }

    private val natCmp = Comparator<LItem> { a, b ->
        val ka = natKey(a.name); val kb = natKey(b.name)
        var r = 0
        for (k in 0 until minOf(ka.size, kb.size)) { r = (ka[k] as String).compareTo(kb[k] as String); if (r != 0) break }
        if (r == 0) r = ka.size.compareTo(kb.size)
        if (r == 0) r = a.id.compareTo(b.id)
        r
    }

    @Synchronized
    private fun sorted(did: Long?, sort: String, dir: String?, types: Set<String>): List<LItem> {
        ensure()
        val defDesc = sort != "name" && sort != "type"
        val desc = when (dir) { "desc" -> true; "asc" -> false; else -> defDesc }
        val key = "$did|$sort|$desc|${types.sorted()}"
        return sortCache.getOrPut(key) {
            val base = all.filter { (did == null || it.bucketId == did) && it.type in types }
            val asc = when (sort) {
                "name" -> base.sortedWith(natCmp)
                "size" -> base.sortedWith(compareBy<LItem> { it.size }.thenBy { it.id })
                "type" -> base.sortedWith(compareBy<LItem> { it.ext }.thenComparing(natCmp))
                "modified" -> base.sortedWith(compareBy<LItem> { it.modMs }.thenBy { it.id })
                "created" -> base.sortedWith(compareBy<LItem> { it.addedMs }.thenBy { it.id })
                else -> base.sortedWith(compareBy<LItem> { it.takenMs }.thenBy { it.id })
            }
            if (desc) asc.asReversed() else asc
        }
    }

    class NotFound : Exception()

    /** 和服务端 history 接口同样的语义:items 按 dir 顺序;nextCursor 继续往后、prevCursor 往前;around / aroundDate 以某处为中心取一页。 */
    fun history(
        did: Long, sort: String, dir: String?, types: Set<String>, cursor: String?, limit: Int, around: String?, aroundDate: String?,
    ): HistoryResp {
        val list = sorted(did, sort, dir, types)
        val total = list.size
        val lim = limit.coerceIn(1, 200)
        val dialogId = did.toString()
        val desc = when (dir) { "desc" -> true; "asc" -> false; else -> sort != "name" && sort != "type" }
        val (start, end) = when {
            around != null -> {
                val idx = list.indexOfFirst { it.id.toString() == around }
                if (idx < 0) throw NotFound()
                window(idx, lim, total)
            }
            aroundDate != null -> {
                val day = runCatching { java.time.LocalDate.parse(aroundDate.take(10)) }.getOrNull() ?: throw NotFound()
                val zone = ZoneId.systemDefault()
                val lo = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val hi = lo + 24 * 3600 * 1000 - 1
                val tk: (LItem) -> Long = { if (sort == "modified") it.modMs else if (sort == "created") it.addedMs else it.takenMs }
                val idx = (if (desc) list.indexOfFirst { tk(it) <= hi } else list.indexOfFirst { tk(it) >= lo }).let { if (it < 0) total - 1 else it }
                window(maxOf(idx, 0), lim, total)
            }
            cursor != null && cursor.startsWith("p:") -> {
                val e = cursor.substring(2).toIntOrNull()?.coerceIn(0, total) ?: 0
                maxOf(e - lim, 0) to e
            }
            cursor != null && cursor.startsWith("n:") -> {
                val s = (cursor.substring(2).toIntOrNull() ?: -1) + 1
                s.coerceIn(0, total) to minOf(s + lim, total)
            }
            else -> 0 to minOf(lim, total)
        }
        val page = list.subList(start, maxOf(start, end)).map { toItem(it, dialogId) }
        return HistoryResp(
            items = page, total = total, firstRank = start,
            nextCursor = if (end < total) "n:${end - 1}" else null,
            prevCursor = if (start > 0) "p:$start" else null,
        )
    }

    private fun window(idx: Int, lim: Int, total: Int): Pair<Int, Int> {
        var s = maxOf(idx - lim / 2, 0)
        val e = minOf(s + lim, total)
        s = maxOf(e - lim, 0)
        return s to e
    }

    fun search(q: String, types: Set<String>, cursor: String?, limit: Int): HistoryResp {
        ensure()
        val term = q.trim().lowercase()
        val hits = all.filter { it.type in types && it.name.lowercase().contains(term) }
            .sortedWith(compareByDescending<LItem> { it.takenMs }.thenByDescending { it.id })
        val off = cursor?.toIntOrNull() ?: 0
        val lim = limit.coerceIn(1, 200)
        val page = hits.drop(off).take(lim).map { toItem(it, it.bucketId.toString()) }
        return HistoryResp(items = page, total = hits.size, nextCursor = if (off + lim < hits.size) (off + lim).toString() else null)
    }

    fun random(types: Set<String>, limit: Int, seed: Long, batch: Int): HistoryResp {
        ensure()
        val lim = limit.coerceIn(1, 120)
        val pool = all.filter { it.type in types }
        // 带 seed:先按 id 排好再用 seed 洗牌,同一个 seed 的顺序固定,按批号切片;不带 seed 每次都不同
        val order = if (seed != 0L) pool.sortedBy { it.id }.shuffled(kotlin.random.Random(seed)) else pool.shuffled()
        val page = order.drop(batch * lim).take(lim).map { toItem(it, it.bucketId.toString()) }
        return HistoryResp(items = page, total = pool.size)
    }

    // ------------------------------------------------------------ 文件与缩略图

    fun openFd(i: LItem): ParcelFileDescriptor? = runCatching { ctx.contentResolver.openFileDescriptor(i.uri, "r") }.getOrNull()

    /** 缩略图(JPEG 字节):Android 10+ 用系统的 loadThumbnail(有系统缓存,很快),更低版本自己解码。 */
    fun thumbnail(i: LItem, size: Int): ByteArray? {
        var bmp: Bitmap? = null
        // 图片:自己从原图缩小(系统缩略图只有几百像素,放大显示会糊,且 ImageDecoder 会处理 EXIF 方向)
        if (!i.video && Build.VERSION.SDK_INT >= 28) bmp = runCatching {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(ctx.contentResolver, i.uri)) { d, info, _ ->
                val m = maxOf(info.size.width, info.size.height)
                if (m > size) {
                    val k = size.toFloat() / m
                    d.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
                }
                d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
        if (bmp == null && Build.VERSION.SDK_INT >= 29) bmp = runCatching { ctx.contentResolver.loadThumbnail(i.uri, Size(size, size), null) }.getOrNull()
        if (bmp == null) bmp = if (i.video) videoFrame(i) else sampled(i, size)
        if (bmp == null) return null
        return ByteArrayOutputStream().use { bos -> bmp.compress(Bitmap.CompressFormat.JPEG, 88, bos); bos.toByteArray() }
    }

    private fun videoFrame(i: LItem): Bitmap? = runCatching {
        val r = MediaMetadataRetriever()
        try { r.setDataSource(ctx, i.uri); r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) } finally { r.release() }
    }.getOrNull()

    private fun sampled(i: LItem, size: Int): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(i.uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (o.outWidth / (s * 2) >= size && o.outHeight / (s * 2) >= size) s *= 2
        ctx.contentResolver.openInputStream(i.uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
    }.getOrNull()

    companion object {
        const val VIDEO_OFFSET = 1_000_000_000_000L
        fun permissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO,
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        /** 是否已有读取媒体的权限(Android 14 的"仅部分照片"也算,只是只能读到选中的)。 */
        fun hasPermission(ctx: Context): Boolean {
            fun ok(p: String) = ctx.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
            return when {
                Build.VERSION.SDK_INT >= 34 -> (ok(android.Manifest.permission.READ_MEDIA_IMAGES) && ok(android.Manifest.permission.READ_MEDIA_VIDEO)) ||
                    ok("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                Build.VERSION.SDK_INT >= 33 -> ok(android.Manifest.permission.READ_MEDIA_IMAGES) || ok(android.Manifest.permission.READ_MEDIA_VIDEO)
                else -> ok(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }
}

/** 只读取 [limit] 字节的 Source,关闭时顺带关掉文件描述符。 */
private class LimitedSource(private val d: Source, private var remaining: Long, private val onClose: () -> Unit) : Source {
    override fun read(sink: Buffer, byteCount: Long): Long {
        if (remaining <= 0) return -1
        val n = d.read(sink, minOf(byteCount, remaining))
        if (n > 0) remaining -= n
        return n
    }
    override fun timeout(): Timeout = d.timeout()
    override fun close() { try { d.close() } finally { onClose() } }
}

/**
 * 本地媒体模式的"服务端":拦截发往 [LocalMode.HOST] 的请求,在进程内按服务端同样的接口格式应答。
 * 这样列表 / 聊天 / 查看器 / 播放器 / 图片加载全部沿用原来的代码(ExoPlayer 和 Coil 用的也是同一个 OkHttp 客户端)。
 */
class LocalInterceptor(private val local: LocalMedia) : Interceptor {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.host != LocalMode.HOST) return chain.proceed(req)
        return try { handle(req) } catch (e: LocalMedia.NotFound) {
            err(req, 404, "notfound", "条目不存在")
        } catch (e: Exception) {
            AppLog.w("local", "本地接口出错 ${req.url.encodedPath}", e)
            err(req, 500, "internal", e.message ?: "出错了")
        }
    }

    private fun handle(req: okhttp3.Request): Response {
        val seg = req.url.pathSegments // api, v1, ...
        val p = seg.drop(2)
        val q = req.url
        fun types(): Set<String> = (q.queryParameter("types") ?: "photo,video,gif").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return when {
            p == listOf("server", "info") -> json(req, """{"name":"本地媒体","version":"本机","apiVersion":1}""")
            p == listOf("auth", "logout") -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(204).message("No Content")
                .body(ByteArray(0).toResponseBody()).build()
            p == listOf("dialogs") -> json(req, AppJson.encodeToString(local.dialogs()))
            p.size == 3 && p[0] == "dialogs" && p[2] == "history" -> {
                val did = p[1].toLongOrNull() ?: throw LocalMedia.NotFound()
                json(
                    req, AppJson.encodeToString(
                        local.history(
                            did, q.queryParameter("sort") ?: "taken", q.queryParameter("dir"), types(), q.queryParameter("cursor"),
                            q.queryParameter("limit")?.toIntOrNull() ?: 60, q.queryParameter("around"), q.queryParameter("aroundDate"),
                        ),
                    ),
                )
            }
            p == listOf("search") -> json(
                req, AppJson.encodeToString(local.search(q.queryParameter("q").orEmpty(), types(), q.queryParameter("cursor"), q.queryParameter("limit")?.toIntOrNull() ?: 60)),
            )
            p == listOf("random", "reset") -> json(req, """{"ok":true}""")
            p == listOf("random") -> json(req, AppJson.encodeToString(local.random(types(), q.queryParameter("limit")?.toIntOrNull() ?: 60, q.queryParameter("seed")?.toLongOrNull() ?: 0L, q.queryParameter("batch")?.toIntOrNull() ?: 0)))
            p.size >= 3 && p[0] == "media" -> {
                val item = local.item(p[1].toLongOrNull() ?: throw LocalMedia.NotFound()) ?: throw LocalMedia.NotFound()
                when (p[2]) {
                    "file" -> file(req, item)
                    "poster" -> thumb(req, item, 720)
                    "render" -> thumb(req, item, (q.queryParameter("w")?.toIntOrNull() ?: 960).coerceIn(64, 4096))
                    else -> err(req, 404, "unsupported", "本地媒体不支持这个功能(例如服务端转码)")
                }
            }
            else -> err(req, 404, "notfound", "没有这个接口")
        }
    }

    private fun json(req: okhttp3.Request, body: String) = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .header("Cache-Control", "no-store").body(body.toResponseBody(jsonType)).build()

    private fun err(req: okhttp3.Request, code: Int, c: String, msg: String) = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message(msg)
        .body("""{"error":{"code":"$c","message":"$msg"}}""".toResponseBody(jsonType)).build()

    private fun thumb(req: okhttp3.Request, item: LocalMedia.LItem, size: Int): Response {
        val bytes = local.thumbnail(item, size) ?: return err(req, 404, "poster.unavailable", "无法生成缩略图")
        return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Cache-Control", "private, max-age=3600").body(bytes.toResponseBody("image/jpeg".toMediaType())).build()
    }

    /** 原文件直出,支持 Range(ExoPlayer 拖动进度 / 探测文件结构都靠它)。 */
    private fun file(req: okhttp3.Request, item: LocalMedia.LItem): Response {
        val pfd = local.openFd(item) ?: return err(req, 404, "gone", "文件已不存在")
        val len = pfd.statSize.takeIf { it >= 0 } ?: item.size
        var start = 0L; var end = len - 1
        var partial = false
        req.header("Range")?.let { r ->
            val m = Regex("bytes=(\\d*)-(\\d*)").find(r)
            if (m != null) {
                val a = m.groupValues[1]; val b = m.groupValues[2]
                if (a.isEmpty() && b.isNotEmpty()) { start = maxOf(len - b.toLong(), 0); end = len - 1 }
                else { start = a.toLongOrNull() ?: 0L; end = if (b.isEmpty()) len - 1 else minOf(b.toLong(), len - 1) }
                partial = true
            }
        }
        if (len == 0L || start >= len || start > end) {
            pfd.close()
            return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(416).message("Range Not Satisfiable")
                .header("Content-Range", "bytes */$len").body(ByteArray(0).toResponseBody()).build()
        }
        val fis = FileInputStream(pfd.fileDescriptor)
        if (start > 0) fis.channel.position(start)
        val n = end - start + 1
        val body = object : ResponseBody() {
            override fun contentType() = item.mime.toMediaTypeOrNull()
            override fun contentLength() = n
            override fun source() = LimitedSource(fis.source(), n) { runCatching { fis.close() }; runCatching { pfd.close() } }.buffer()
        }
        val b = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).header("Accept-Ranges", "bytes").body(body)
        return if (partial) b.code(206).message("Partial Content").header("Content-Range", "bytes $start-$end/$len").build()
        else b.code(200).message("OK").build()
    }
}

private fun String.toMediaTypeOrNull(): okhttp3.MediaType? = runCatching { this.toMediaType() }.getOrNull()
