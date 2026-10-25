package com.localtg.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The selection is fixed when a viewer opens; changing tabs later cannot widen it. */
data class SlideScope(val types: String, val dialog: String? = null, val roots: String? = null, val search: String? = null) {
    val mediaTypes get() = types.split(',').filter { it in setOf("photo", "gif", "video") }.distinct().joinToString(",")
    fun accepts(item: Item) = item.type in mediaTypes.split(',')
}
fun slideSeconds(value: Float): Float = if (value.isFinite()) value.coerceIn(0.5f, 10f) else 5f
data class SlideshowOptions(val imageSeconds: Float = 5f, val order: String = "forward")
data class SlideGroupOrder(val sort: String, val direction: String)

interface SlideLibrary {
    suspend fun history(scope: SlideScope, order: SlideGroupOrder, cursor: String?, around: String?): HistoryResp
    suspend fun shuffle(scope: SlideScope, seed: Long, cursor: String?): HistoryResp
}
class ApiSlideLibrary(private val api: Api) : SlideLibrary {
    override suspend fun history(scope: SlideScope, order: SlideGroupOrder, cursor: String?, around: String?) =
        api.history(scope.dialog!!, order.sort, order.direction, scope.mediaTypes, cursor, 61, around)
    override suspend fun shuffle(scope: SlideScope, seed: Long, cursor: String?) = api.shuffle(scope.copy(types = scope.mediaTypes), seed, cursor)
}

/** Shared continuation, also used by normal viewer prefetch. Advance only after success. */
class SlideSequence(initial: List<Item>, private val fetch: (suspend () -> Pair<List<Item>, Boolean>)? = null) {
    val items = initial.distinctBy { it.id }.toMutableList()
    var exhausted = fetch == null; private set
    private val mutex = Mutex()
    suspend fun more(): Pair<List<Item>, Boolean> = mutex.withLock {
        if (exhausted) return@withLock emptyList<Item>() to false
        val (page, hasMore) = fetch!!.invoke()
        val known = items.mapTo(HashSet()) { it.id }
        val added = page.filter { known.add(it.id) }
        items.addAll(added); exhausted = !hasMore
        added to hasMore
    }
}
data class SlideSource(val scope: SlideScope, val groupOrder: SlideGroupOrder? = null, val sequence: SlideSequence? = null, val origin: String? = scope.dialog)

/** Every order starts on the item currently on screen; never silently fall back to page one. */
suspend fun createSlideshow(source: SlideSource, library: SlideLibrary, anchor: Item, options: SlideshowOptions, seed: Long): ViewerFeed {
    require(source.scope.accepts(anchor)) { "当前条目不在图片或视频筛选范围内" }
    require(options.order in setOf("forward", "reverse", "shuffle"))
    val scope = source.scope
    if (options.order == "shuffle") {
        val first = library.shuffle(scope, seed, null) // Check support/network before changing the active viewer.
        var cursor = first.nextCursor
        val sequence = SlideSequence((listOf(anchor) + first.items).filter(scope::accepts)) {
            val next = cursor
            if (next == null) emptyList<Item>() to false else {
                val page = library.shuffle(scope, seed, next)
                cursor = page.nextCursor
                page.items.filter(scope::accepts) to (cursor != null)
            }
        }
        val queueSource = if (source.groupOrder != null) source else source.copy(sequence = sequence)
        return sequenceFeed(sequence, queueSource, 0, returnAnchorId = anchor.id.takeIf { source.groupOrder != null })
    }
    source.groupOrder?.let { base ->
        val order = if (options.order == "reverse") base.copy(direction = if (base.direction == "asc") "desc" else "asc") else base
        val first = library.history(scope, order, null, anchor.id)
        val at = first.items.indexOfFirst { it.id == anchor.id }
        check(at >= 0) { "当前条目已被移动或删除，请重新打开后播放" }
        var cursor = first.nextCursor
        return ViewerFeed((listOf(anchor) + first.items.drop(at + 1)).filter(scope::accepts), originDialogId = source.origin, slideSource = source) { _, _ ->
            val next = cursor
            if (next == null) emptyList<Item>() to null else {
                val page = library.history(scope, order, next, null)
                cursor = page.nextCursor
                page.items.filter(scope::accepts) to cursor
            }
        }
    }
    val sequence = requireNotNull(source.sequence)
    val at = sequence.items.indexOfFirst { it.id == anchor.id }
    check(at >= 0) { "当前条目不在播放队列中，请重新打开后播放" }
    if (options.order == "reverse") return ViewerFeed(sequence.items.take(at + 1).asReversed().filter(scope::accepts), originDialogId = source.origin, slideSource = source)
    return sequenceFeed(sequence, source, at)
}

private fun sequenceFeed(sequence: SlideSequence, source: SlideSource, from: Int, returnAnchorId: String? = null): ViewerFeed {
    val initial = sequence.items.drop(from).filter(source.scope::accepts)
    var emitted = sequence.items.size
    return ViewerFeed(initial, originDialogId = source.origin, slideSource = source, returnAnchorId = returnAnchorId) { _, _ ->
        // A cancelled normal-viewer prefetch may already have appended a page. Consume it before fetching again.
        if (emitted >= sequence.items.size && !sequence.exhausted) sequence.more()
        val page = sequence.items.drop(emitted).filter(source.scope::accepts)
        emitted = sequence.items.size
        page to if (sequence.exhausted) null else "more"
    }
}
