package com.localtg.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.paging.compose.LazyPagingItems
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import com.localtg.data.Api
import com.localtg.data.Item
import com.localtg.ui.tg.LocalSettings
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 预加载:列表滑到(或跳到)某处、停下来之后,把那附近(屏幕外)的缩略图提前下载进 Coil 的磁盘缓存,
 * 再往那边滑时直接从本机读,不用等网络。
 *  - 范围 = 设置里的"预加载数量"个缩略图,滑动方向那一侧占 2/3,另一侧 1/3,离屏幕近的先加载;
 *  - 滑得很快时(位置还在变)不加载,停下 / 慢下来 150ms 后才开始 —— 不会和屏幕上正在显示的图抢带宽;
 *  - 只进磁盘缓存、不进内存缓存:几十上百张图塞进内存缓存会把屏幕上的图挤掉;磁盘读 + 解码很快;
 *  - 只处理已经加载了列表数据的位置(分页数据没到的位置没有条目可预加载)。
 * 索引方向与屏幕方向无关(聊天流是反向布局),只看索引的增减,所以聊天流和网格共用。
 */
@OptIn(FlowPreview::class)
@Composable
fun PreloadNear(items: LazyPagingItems<Item>, api: Api, width: Int, first: () -> Int, last: () -> Int) {
    val n = LocalSettings.current.preloadCount
    if (n <= 0) return
    val ctx = LocalContext.current.applicationContext
    val loader = remember { SingletonImageLoader.get(ctx) }
    val done = remember(width) { HashSet<String>() } // 已经提交过的条目,不重复请求
    LaunchedEffect(n, width) {
        var prevFirst = first()
        snapshotFlow { first() to last() }
            .distinctUntilChanged()
            .debounce(150)
            .collect { (f, l) ->
                val forward = f >= prevFirst
                prevFirst = f
                val ahead = n * 2 / 3
                val back = n - ahead
                val from = f - (if (forward) back else ahead)
                val to = l + (if (forward) ahead else back)
                val count = items.itemCount
                if (count == 0) return@collect
                val order = (maxOf(from, 0)..minOf(to, count - 1))
                    .filter { it < f || it > l }
                    .sortedBy { if (it < f) f - it else it - l } // 离屏幕近的先
                for (i in order) {
                    val item = items.peek(i) ?: continue
                    if (done.add(item.id)) preloadThumb(ctx, loader, api, item, width)
                }
            }
    }
}

/** 把一张缩略图(图片 = 列表里显示的那个地址,视频 = 封面)预加载进磁盘缓存,和列表里的请求用同一个地址,所以之后直接命中。 */
fun preloadThumb(ctx: Context, loader: ImageLoader, api: Api, item: Item, width: Int) {
    if (item.brokenLabel() != null || !item.canShowNatively()) return
    val url = if (item.isVideo) api.posterUrl(item) else api.imageUrl(item, width)
    loader.enqueue(
        ImageRequest.Builder(ctx).data(url)
            .size(Size.ORIGINAL)
            .memoryCachePolicy(CachePolicy.DISABLED) // 只进磁盘
            .build(),
    )
}
