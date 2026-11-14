package com.localtg.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.localtg.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 直接播放的视频的磁盘缓存(上限由本地缓存的智能分配决定,见 CacheBudget;LRU):
 *  - 往回拖、重看一遍,已经下载过的部分直接读本地,不再走网络(经穿透 / 移动网络时最明显);
 *  - 翻页时提前把下一个直放视频的开头几 MB 缓存好(只在不计流量的网络下),切过去几乎秒开。
 * 缓存"原文件 / 兼容版本"(路径 /file、/compat)和转码的 HLS 分段(.ts);播放列表 .m3u8 不缓存。缓存键是未改写的原地址,内网直连切线路后仍然命中。
 */
@UnstableApi
object VideoCache {
    private var cache: SimpleCache? = null
    private var sizeMb = -1

    /** 视频缓存:上限 = 本地缓存智能分配给"视频"的额度(应用启动时定好,运行中不变)。 */
    @Synchronized
    fun get(ctx: Context): SimpleCache? {
        val bytes = com.localtg.data.CacheBudget.current[com.localtg.data.CacheBudget.Kind.Video]
        if (bytes <= 0) return null
        val mb = (bytes / (1024L * 1024L)).toInt()
        if (cache != null && sizeMb == mb) return cache
        runCatching { cache?.release() }
        cache = runCatching {
            SimpleCache(File(ctx.cacheDir, "video"), LeastRecentlyUsedCacheEvictor(bytes), StandaloneDatabaseProvider(ctx.applicationContext))
        }.getOrNull()
        sizeMb = mb
        return cache
    }

    /** 当前占用(没打开过缓存时按目录大小算)。 */
    @Synchronized
    fun size(ctx: Context): Long = cache?.cacheSpace ?: File(ctx.cacheDir, "video").walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** 清空视频缓存:逐个删掉缓存里的条目(不在播放时调用;正在被读的分段会在读完后才真正删除)。 */
    @Synchronized
    fun clear(ctx: Context) {
        val c = get(ctx) ?: return
        for (key in c.keys.toList()) runCatching { c.removeResource(key) }
    }

    /** 走到网络的视频数据(缓存没命中)记作视频的需求;同时转给播放器的网速统计。 */
    private class Meter(private val inner: TransferListener?) : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { inner?.onTransferInitializing(source, dataSpec, isNetwork) }
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { inner?.onTransferStart(source, dataSpec, isNetwork) }
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork) com.localtg.data.CacheBudget.record(com.localtg.data.CacheBudget.Kind.Video, bytesTransferred.toLong())
            inner?.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred)
        }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { inner?.onTransferEnd(source, dataSpec, isNetwork) }
    }

    /**
     * 走磁盘缓存的请求:原文件 / 兼容版本(按字节区间缓存,拖到哪段下载哪段,拖回已下载过的区间直接读本地),
     * 以及转码的 HLS 分段(.../hls/seg00012.ts?码率&编码&sid)。同一个视频、同一档画质在这次运行里分段地址固定、内容不变,
     * 拖到最后、拖回最前、再拖回最后,已经下过的分段都从本地读,不会重复下载(服务端为了省空间可能已经删掉了远处的分段,
     * 不缓存的话还得重新转码)。播放列表 .m3u8 会随转码进度变化,不缓存。
     */
    private fun cacheable(uri: Uri): Boolean {
        val p = uri.path.orEmpty()
        return p.endsWith("/file") || p.endsWith("/compat") || (p.contains("/hls/") && p.endsWith(".ts"))
    }

    /** 播放器用的数据源工厂:缓存开着时,原文件 / 兼容版本 / HLS 分段走缓存,其它(播放列表等)直接走网络。 */
    fun factory(ctx: Context, c: AppContainer, meter: TransferListener?): DataSource.Factory {
        val upstream = OkHttpDataSource.Factory(c.http).apply { if (meter != null) setTransferListener(meter) }
        val cache = get(ctx) ?: return upstream
        // 走缓存的那一路:上游下载的字节(缓存没命中的部分)记作视频需求
        val cachedUpstream = OkHttpDataSource.Factory(c.http).apply { setTransferListener(Meter(meter)) }
        val cached = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(cachedUpstream).setCacheKeyFactory(KeyNoSid).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DataSource.Factory { Router(cached.createDataSource(), upstream.createDataSource()) }
    }

    /** 缓存键:去掉转码会话标识 sid(每次播放都不同,带着它同一视频同一画质的分段重新打开就全部失效)。 */
    private val KeyNoSid = androidx.media3.datasource.cache.CacheKeyFactory { spec ->
        val u = spec.uri
        if (u.getQueryParameter("sid") == null) spec.key ?: u.toString()
        else {
            val b = u.buildUpon().clearQuery()
            for (n in u.queryParameterNames) if (n != "sid") for (v in u.getQueryParameters(n)) b.appendQueryParameter(n, v)
            b.build().toString()
        }
    }

    private class Router(private val cached: DataSource, private val plain: DataSource) : DataSource {
        private var cur: DataSource = plain
        private val listeners = ArrayList<TransferListener>()
        override fun addTransferListener(l: TransferListener) { listeners.add(l); cached.addTransferListener(l); plain.addTransferListener(l) }
        override fun open(dataSpec: DataSpec): Long {
            cur = if (cacheable(dataSpec.uri)) cached else plain
            return cur.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = cur.read(buffer, offset, length)
        override fun getUri(): Uri? = cur.uri
        override fun getResponseHeaders(): Map<String, List<String>> = cur.responseHeaders
        override fun close() { cur.close() }
    }

    /**
     * 提前缓存 url 开头 bytes 字节(bytes = C.LENGTH_UNSET 表示整个文件)。默认只在网络不计流量(Wi-Fi / 内网直连)时做,
     * metered = true 时移动网络也做;协程取消就停。已经缓存过的部分不会重复下载。
     */
    suspend fun prefetch(ctx: Context, c: AppContainer, url: String, bytes: Long = 3L shl 20, metered: Boolean = false) = withContext(Dispatchers.IO) {
        val cache = get(ctx) ?: return@withContext
        if (!metered && isMetered(ctx)) return@withContext
        val up = OkHttpDataSource.Factory(c.http).apply { setTransferListener(Meter(null)) }
        val ds = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(up).createDataSource()
        val writer = CacheWriter(ds, DataSpec.Builder().setUri(url).setPosition(0).setLength(bytes).build(), null, null)
        currentCoroutineContext().job.invokeOnCompletion { writer.cancel() }
        runCatching { writer.cache() }
    }

    private fun isMetered(ctx: Context): Boolean =
        (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.isActiveNetworkMetered != false

    // ---------------------------------------------------------------- 短视频预加载

    /** 20 秒以内的短视频:整个文件不大,看到就预加载,点开直接从本地播放。 */
    const val SHORT_MS = 20_000L
    private const val SHORT_MAX_BYTES = 60L shl 20

    fun isShort(item: com.localtg.data.Item): Boolean =
        item.isVideo && (item.durationMs ?: 0L) in 1..SHORT_MS && item.size in 1..SHORT_MAX_BYTES && item.brokenLabel() == null

    /** 这个视频的原文件是否已经完整在缓存里(是的话播放器直接读本地,不转码)。 */
    fun isFullyCached(ctx: Context, c: AppContainer, item: com.localtg.data.Item): Boolean =
        item.size > 0 && runCatching { get(ctx)?.isCached(c.api.fileUrl(item), 0, item.size) == true }.getOrDefault(false)

    private val preloading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val preloadGate = kotlinx.coroutines.sync.Semaphore(2)

    /**
     * 预加载短视频的整个文件(设置 → 视频播放 → 短视频预加载:always 总是 / wifi 仅 Wi-Fi / off 关闭)。
     * 列表里滑到屏幕附近的、查看器里相邻的短视频都会调用;同时最多下 2 个,同一个视频只下一次,已经完整缓存的直接跳过。
     */
    fun preloadShort(ctx: Context, c: AppContainer, item: com.localtg.data.Item) {
        val mode = c.settings.value.shortVideoPreload
        if (mode == "off" || c.isLocal || !isShort(item)) return
        if (mode == "wifi" && isMetered(ctx)) return
        if (isFullyCached(ctx, c, item) || !preloading.add(item.id)) return
        val app = ctx.applicationContext
        c.scope.launch(Dispatchers.IO) {
            try {
                preloadGate.withPermit { prefetch(app, c, c.api.fileUrl(item), androidx.media3.common.C.LENGTH_UNSET.toLong(), metered = true) }
            } catch (e: Exception) {
                com.localtg.AppLog.w("video", "短视频预加载失败 ${item.name}: ${e.message}")
            } finally { preloading.remove(item.id) }
        }
    }
}
