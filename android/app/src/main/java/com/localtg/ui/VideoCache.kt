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
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 直接播放的视频的磁盘缓存(设置 → 视频缓存,默认 1GB,LRU):
 *  - 往回拖、重看一遍,已经下载过的部分直接读本地,不再走网络(经穿透 / 移动网络时最明显);
 *  - 翻页时提前把下一个直放视频的开头几 MB 缓存好(只在不计流量的网络下),切过去几乎秒开。
 * 只缓存"原文件 / 兼容版本"(路径 /file、/compat);转码的 HLS 分段是临时的,不进缓存。缓存键是未改写的原地址,内网直连切线路后仍然命中。
 */
@UnstableApi
object VideoCache {
    private var cache: SimpleCache? = null
    private var sizeMb = -1

    @Synchronized
    fun get(ctx: Context, mb: Int): SimpleCache? {
        if (mb <= 0) return null
        if (cache != null && sizeMb == mb) return cache
        runCatching { cache?.release() }
        cache = runCatching {
            SimpleCache(File(ctx.cacheDir, "video"), LeastRecentlyUsedCacheEvictor(mb * 1024L * 1024L), StandaloneDatabaseProvider(ctx))
        }.getOrNull()
        sizeMb = mb
        return cache
    }

    private fun cacheable(uri: Uri): Boolean {
        val p = uri.path.orEmpty()
        return p.endsWith("/file") || p.endsWith("/compat")
    }

    /** 播放器用的数据源工厂:缓存开着时,原文件 / 兼容版本走缓存,其它(HLS 等)直接走网络。 */
    fun factory(ctx: Context, c: AppContainer, meter: TransferListener?): DataSource.Factory {
        val upstream = OkHttpDataSource.Factory(c.http).apply { if (meter != null) setTransferListener(meter) }
        val cache = get(ctx, c.settings.value.videoCacheMb) ?: return upstream
        val cached = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DataSource.Factory { Router(cached.createDataSource(), upstream.createDataSource()) }
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

    /** 提前缓存 url 开头 bytes 字节。只在有缓存、网络不计流量(Wi-Fi / 内网直连)时做;协程取消就停。 */
    suspend fun prefetch(ctx: Context, c: AppContainer, url: String, bytes: Long = 3L shl 20) = withContext(Dispatchers.IO) {
        val cache = get(ctx, c.settings.value.videoCacheMb) ?: return@withContext
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm?.isActiveNetworkMetered != false) return@withContext
        val ds = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(OkHttpDataSource.Factory(c.http)).createDataSource()
        val writer = CacheWriter(ds, DataSpec.Builder().setUri(url).setPosition(0).setLength(bytes).build(), null, null)
        currentCoroutineContext().job.invokeOnCompletion { writer.cancel() }
        runCatching { writer.cache() }
    }
}
