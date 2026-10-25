package com.localtg.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException

/**
 * 查看器的数据源:先给已经加载好的条目,翻到接近末尾时用 [loader] 继续取更多(聊天历史或搜索结果),
 * 这样不会只能翻到聊天页恰好加载过的那一页。
 * loader(cursor, lastId) 返回 (新条目, 下一个 cursor);下一个 cursor 为 null 表示没有更多了。
 */
class ViewerFeed(
    initial: List<Item>,
    /** 屏幕上"上一个"(上面那条 / 前一格)是不是列表里序号更大的那一项:聊天流(最新在底部)里是,网格 / 搜索结果里不是 */
    val prevIsHigherIndex: Boolean = false,
    /** 从哪个群组打开的(搜索结果等没有就是 null):在查看器里滑到哪一项,退出后这个群组要定位到那一项。 */
    val originDialogId: String? = null,
    val slideSource: SlideSource? = null,
    /** Group shuffle returns to the item where that queue began, even after playback stops. */
    val returnAnchorId: String? = null,
    private val onLoadError: (Exception) -> Unit = { com.localtg.AppLog.w("viewer", "加载更多失败", it) },
    private val loader: (suspend (cursor: String?, lastId: String?) -> Pair<List<Item>, String?>)? = null,
) {
    val items = mutableStateListOf<Item>().apply { addAll(initial) }
    private var cursor: String? = null
    private var started = false
    var exhausted by mutableStateOf(loader == null)
        private set
    private val loadMutex = Mutex()

    fun returnPosition(current: Item): Pair<String, String>? = originDialogId?.let { it to (returnAnchorId ?: current.id) }

    suspend fun loadMore(): Boolean = loadMutex.withLock {
        val l = loader ?: return@withLock false
        if (exhausted) return@withLock false
        try {
            val (more, next) = l(if (started) cursor else null, items.lastOrNull()?.id)
            started = true
            val known = items.mapTo(HashSet()) { it.id }
            val added = more.filter { known.add(it.id) }
            items.addAll(added)
            cursor = next
            if (next == null) exhausted = true
            added.isNotEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onLoadError(e)
            false
        }
    }
}
