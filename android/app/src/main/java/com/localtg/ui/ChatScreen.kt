package com.localtg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.rememberCoroutineScope
import com.localtg.AppContainer
import com.localtg.AppLog
import com.localtg.data.Api
import com.localtg.ui.tg.LocalSettings
import com.localtg.data.DialogView
import com.localtg.data.ApiException
import com.localtg.data.Item
import com.localtg.ui.tg.BarIcon
import com.localtg.ui.tg.LocalTg
import com.localtg.ui.tg.Pill
import com.localtg.ui.tg.TgAvatar
import com.localtg.ui.tg.TgBar
import com.localtg.ui.tg.TgIcons
import com.localtg.ui.tg.toItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.abs

/** 分页键:cursor 带方向(向前/向后翻页),around 表示"以某条为中心取一页"(用于恢复上次位置)。 */
private data class HKey(val cursor: String? = null, val around: String? = null)

/** 双向分页读取对话历史:从任意位置开始,向前、向后都能继续翻。 */
private class HistorySource(
    private val api: Api, private val dialogId: String,
    private val sort: String, private val dir: String, private val types: String,
    private val onTotal: (Int) -> Unit,
) : PagingSource<HKey, Item>() {
    override fun getRefreshKey(state: PagingState<HKey, Item>): HKey? =
        state.anchorPosition?.let { state.closestItemToPosition(it) }?.let { HKey(around = it.id) }

    override suspend fun load(params: LoadParams<HKey>): LoadResult<HKey, Item> = try {
        val key = params.key
        AppLog.d("chat", "加载 dialog=$dialogId sort=$sort dir=$dir types=$types key=${key?.cursor?.take(24) ?: key?.around?.let { "around:$it" } ?: "起点"} size=${params.loadSize}")
        val r = try {
            api.history(dialogId, sort, dir, types, key?.cursor, params.loadSize, key?.around)
        } catch (e: ApiException) {
            // 上次停留的条目已不存在(被删除/移走)→ 退回从头开始
            if (key?.around != null && e.http == 404) api.history(dialogId, sort, dir, types, null, params.loadSize) else throw e
        }
        onTotal(r.total)
        AppLog.d("chat", "  -> ${r.items.size} 项 total=${r.total} prev=${r.prevCursor != null} next=${r.nextCursor != null}")
        LoadResult.Page(
            data = r.items,
            prevKey = r.prevCursor?.let { HKey(cursor = it) },
            nextKey = r.nextCursor?.let { HKey(cursor = it) },
        )
    } catch (e: Exception) {
        AppLog.w("chat", "加载失败 dialog=$dialogId", e)
        LoadResult.Error(e)
    }
}

val SORT_KEYS = listOf("taken" to "按时间", "name" to "按名称", "size" to "按大小", "type" to "按类型")

private data class Query(val sort: String, val dir: String, val types: Set<String>, val nonce: Int = 0)

class ChatViewModel(private val c: AppContainer, private val dialogId: String) : ViewModel() {
    private val defaultTypes = setOf("photo", "video", "gif")
    private val ns = c.session.ns() // 打开时是哪台服务器(退出时保存位置要写回它,不是当时的"当前服务器")
    private val startBase = c.session.session.value?.baseUrl
    private var serverJob: Job? = null
    val ready = MutableStateFlow(false)
    // 服务器上的文件夹默认按文件名排序;本地媒体默认按文件时间
    val sortKey = MutableStateFlow(if (c.isLocal) "taken" else "name")
    /** 聊天流从上到下是否为正序(时间:旧→新,最新在最底部,和 Telegram 一样)。 */
    val ascChat = MutableStateFlow(true)
    /** 网格从左上到右下是否为正序(默认新→旧)。 */
    val ascGrid = MutableStateFlow(!c.isLocal) // 按名称时网格从 a 到 z(从前到后);按时间时新的在前
    val grid = MutableStateFlow(false)
    val types = MutableStateFlow(defaultTypes)
    val columns = MutableStateFlow(3)
    val total = MutableStateFlow(0)

    /** 每次重建 Pager 加一;界面据此决定要不要重新执行"恢复位置"。 */
    val generation = MutableStateFlow(0)
    /** 回到最新(或顶部)时 +1,强制重建 Pager。 */
    private val reload = MutableStateFlow(0)
    private var lastQuery: Query? = null
    /** 要恢复到的条目(null = 从最新一端开始)与像素偏移;恢复完成后清空,避免从查看器返回时再次跳回去。 */
    var anchorId: String? = null
        private set
    var anchorOffset: Int = 0
        private set

    /** 还没改过视图设置:第一次显示的列表一定是刚加载的新数据(切换视图后则可能暂时还是旧数据)。 */
    var freshOpen: Boolean = true
        private set

    private var pos: Pair<String?, Int>? = null // 当前屏幕上的第一项(null 表示正处于最新一端)
    private var saveJob: Job? = null

    init {
        val st = c.settings.value
        AppLog.i("chat", "打开 dialog=$dialogId rememberPosition=${st.rememberPosition}")
        columns.value = st.defaultColumns.coerceIn(2, 6)
        ascChat.value = st.newestAtBottom
        // 设置里的默认排序 / 方向(各文件夹记住的视图优先)
        if (st.defaultSort != "auto") sortKey.value = st.defaultSort
        ascGrid.value = sortKey.value == "name" || sortKey.value == "type"
        when (st.defaultSortDir) {
            "asc" -> { ascChat.value = true; ascGrid.value = true }
            "desc" -> { ascChat.value = false; ascGrid.value = false }
        }
        if (c.isLocal && st.defaultSort == "auto" && st.defaultSortDir == "auto") ascGrid.value = false
        viewModelScope.launch {
            // 先问服务器(换设备也回到上次的位置),没有记录或连不上再用本机保存的
            val remote = if (st.rememberPosition && !c.isLocal)
                kotlinx.coroutines.withTimeoutOrNull(2500) { c.api.getDialogView(dialogId) } else null
            (if (st.rememberPosition) remote ?: c.viewState.load(dialogId, ns) else null)?.let { v ->
                sortKey.value = v.sort; ascChat.value = v.ascChat; ascGrid.value = v.ascGrid
                grid.value = v.grid; columns.value = v.columns.coerceIn(2, 6)
                types.value = v.types.toSet().ifEmpty { defaultTypes }
                anchorId = v.itemId; anchorOffset = v.offsetPx
                AppLog.i("chat", "恢复位置 item=${v.itemId} offset=${v.offsetPx} grid=${v.grid} sort=${v.sort}")
            }
            ready.value = true
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: Flow<PagingData<Item>> = ready.filter { it }.flatMapLatest {
        combine(
            combine(sortKey, ascChat, ascGrid, grid, types) { s, ac, ag, g, t ->
                // 聊天流使用反向布局(列表第 0 项在最底部),所以服务端方向与"从上到下的正序"相反
                Query(s, if (g) (if (ag) "asc" else "desc") else (if (ac) "desc" else "asc"), t)
            },
            reload,
        ) { q, n -> q.copy(nonce = n) }.distinctUntilChanged().flatMapLatest { q ->
            lastQuery = q
            generation.value += 1
            Pager(
                PagingConfig(pageSize = 60, prefetchDistance = 30, initialLoadSize = 60, enablePlaceholders = false, maxSize = 3000),
                initialKey = HKey(around = anchorId),
            ) {
                HistorySource(c.api, dialogId, q.sort, q.dir, q.types.joinToString(","), onTotal = { total.value = it })
            }.flow
        }
    }.cachedIn(viewModelScope)

    /** 修改视图设置。anchor = 要保持位置的条目(切换聊天/网格时用当前条目);null 表示回到最新一端。 */
    fun change(anchor: String?, block: () -> Unit) {
        anchorId = anchor; anchorOffset = 0; pos = null; freshOpen = false
        block()
        persistSoon()
    }

    /** 回到最新一端(聊天流的最底部 / 网格的最顶部):丢掉位置锚点,从最新重新加载。 */
    fun jumpToNewest() {
        anchorId = null; anchorOffset = 0; pos = null; freshOpen = false
        reload.value += 1
        persistSoon()
    }

    /** 跳转到某一天(date = yyyy-MM-dd):取该日附近的一条作为锚点,重建列表并滚到那里。返回是否成功。 */
    suspend fun jumpToDate(date: String): Boolean {
        val q = lastQuery ?: return false
        return try {
            // limit=2:返回 [该日之前的一条?, 该日当天或更靠后的第一条];只有一条时它就是最近的那条
            val r = c.api.history(dialogId, q.sort, q.dir, q.types.joinToString(","), null, 2, aroundDate = date)
            val it = (if (r.items.size >= 2) r.items[1] else r.items.firstOrNull()) ?: return false
            anchorId = it.id; anchorOffset = 0; pos = null; freshOpen = false
            reload.value += 1
            persistSoon()
            true
        } catch (e: Exception) {
            AppLog.w("chat", "跳转到日期失败 $date:${e.message}")
            false
        }
    }

    /** 查看器翻到已加载内容的末尾时,用它继续取更多(沿用当前的排序 / 方向 / 类型)。 */
    fun moreLoader(): (suspend (String?, String?) -> Pair<List<Item>, String?>)? {
        val q = lastQuery ?: return null
        val types = q.types.joinToString(",")
        return { cursor, lastId ->
            if (cursor == null) { // 第一次:以最后一项为中心取一页,只要它之后的部分
                val r = c.api.history(dialogId, q.sort, q.dir, types, null, 61, around = lastId)
                val idx = r.items.indexOfFirst { it.id == lastId }
                (if (idx >= 0) r.items.drop(idx + 1) else r.items) to r.nextCursor
            } else {
                val r = c.api.history(dialogId, q.sort, q.dir, types, cursor, 60)
                r.items to r.nextCursor
            }
        }
    }

    fun setColumns(n: Int) { columns.value = n; persistSoon() }

    /** 界面上报当前位置(滚动时频繁调用,内部去抖后保存)。atEnd = 正处于最新一端。 */
    fun reportPosition(itemId: String, offsetPx: Int, atEnd: Boolean) {
        pos = (if (atEnd) null else itemId) to offsetPx
        persistSoon()
    }

    /** 当前位置的条目 id(切换视图时作为锚点)。 */
    fun currentItemId(): String? = pos?.first

    /** 位置已恢复:清掉锚点,之后重新进入(例如从查看器返回)不会再次跳动。 */
    fun anchorConsumed() { anchorId = null; anchorOffset = 0 }

    private fun persistSoon() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(400); persist() }
    }

    /** 离开页面时立即保存;用应用级协程域,界面销毁后也能写完。 */
    fun persistNow() { saveJob?.cancel(); persist(now = true) }

    private fun persist(now: Boolean = false) {
        if (!c.settings.value.rememberPosition) return
        val p = pos
        val v = DialogView(
            itemId = if (p != null) p.first else anchorId, offsetPx = p?.second ?: anchorOffset,
            sort = sortKey.value, ascChat = ascChat.value, ascGrid = ascGrid.value, grid = grid.value,
            types = types.value.toList(), columns = columns.value,
        )
        c.scope.launch { c.viewState.save(dialogId, v, ns) }
        // 同步到服务器:滚动时 3 秒去抖,离开页面时立即发;还是打开时那台服务器才发
        if (!c.isLocal && c.session.session.value?.baseUrl == startBase) {
            serverJob?.cancel()
            serverJob = c.scope.launch {
                if (!now) delay(3000)
                runCatching { c.api.putDialogView(dialogId, v) }.onFailure { AppLog.d("chat", "同步浏览位置失败: ${it.message}") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(c: AppContainer, dialogId: String, titleHint: String? = null, onBack: () -> Unit, onOpenViewer: (Int) -> Unit) {
    val vm: ChatViewModel = viewModel(key = "chat-$dialogId", factory = viewModelFactory { initializer { ChatViewModel(c, dialogId) } })
    val lazyItems = vm.items.collectAsLazyPagingItems()
    val tg = LocalTg.current
    val motionStyle = LocalSettings.current.motion
    val grid by vm.grid.collectAsState()
    val sortKey by vm.sortKey.collectAsState()
    val ascChat by vm.ascChat.collectAsState()
    val ascGrid by vm.ascGrid.collectAsState()
    val types by vm.types.collectAsState()
    val total by vm.total.collectAsState()
    val columns by vm.columns.collectAsState()
    val gen by vm.generation.collectAsState()
    val ready by vm.ready.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var datePicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // 群信息:先取列表缓存;缓存里没有(列表还没加载完 / 进程重启后直接回到这里)就单独向服务器取一次,标题先用点开时带来的
    var dialog by remember(dialogId) { mutableStateOf(c.dialogCache[dialogId]) }
    LaunchedEffect(dialogId) {
        if (dialog == null) dialog = c.dialogCache[dialogId] ?: runCatching { c.api.dialog(dialogId) }.getOrNull()
    }
    // 本次 Pager 对应的要恢复的位置(恢复完成后 VM 会清空锚点)
    val restoreId = remember(gen) { vm.anchorId }
    val restoreOffset = remember(gen) { vm.anchorOffset }
    DisposableEffect(Unit) { onDispose { vm.persistNow() } }

    val open: (Item) -> Unit = { item ->
        val snap = lazyItems.itemSnapshotList.items
        c.viewerFeed = com.localtg.data.ViewerFeed(snap, prevIsHigherIndex = !grid, loader = vm.moreLoader())
        onOpenViewer(snap.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize()) {
        // ---- 顶栏:返回 | 群头像 | 标题+副标题 | 右上角 网格/聊天 切换 | 更多 ----
        TgBar {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                BarIcon(TgIcons.Back, "返回", onBack)
                TgAvatar(dialog?.title ?: titleHint ?: "#", dialogId, 40.dp, dialog?.last?.toItem(dialogId), c.api)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(dialog?.title ?: titleHint ?: "文件夹", color = tg.barText, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = dialog?.counts?.let { k ->
                        listOfNotNull(
                            k.photo.takeIf { it > 0 }?.let { "$it 张照片" },
                            k.video.takeIf { it > 0 }?.let { "$it 个视频" },
                            k.gif.takeIf { it > 0 }?.let { "$it 个动图" },
                        ).joinToString(" · ")
                    }.orEmpty().ifEmpty { "$total 项" }
                    Text(sub, color = tg.barSub, fontSize = 13.sp, maxLines = 1)
                }
                // 右上角:点击在"聊天流"与"网格"之间切换
                BarIcon(if (grid) TgIcons.Chat else TgIcons.Grid, if (grid) "聊天视图" else "网格视图") { vm.change(vm.currentItemId()) { vm.grid.value = !grid } } // 切换视图时保持在当前这一条
                Box {
                    BarIcon(TgIcons.More, "更多") { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        SORT_KEYS.forEach { (k, label) ->
                            DropdownMenuItem(text = { Text((if (k == sortKey) "✓  " else "     ") + label) }, onClick = { vm.change(null) { vm.sortKey.value = k; vm.ascGrid.value = (k == "name" || k == "type") } })
                        }
                        if (sortKey == "taken") {
                            DropdownMenuItem(text = { Text("跳转到日期…") }, onClick = { menu = false; datePicker = true })
                        }
                        HorizontalDivider()
                        val asc = if (grid) ascGrid else ascChat
                        DropdownMenuItem(
                            text = { Text(if (grid) (if (asc) "顺序:从前到后" else "顺序:从后到前") else (if (asc) "顺序:旧 → 新(最新在底部)" else "顺序:新 → 旧")) },
                            onClick = { vm.change(null) { if (grid) vm.ascGrid.value = !asc else vm.ascChat.value = !asc } },
                        )
                        HorizontalDivider()
                        listOf("photo" to "图片", "video" to "视频", "gif" to "GIF").forEach { (k, label) ->
                            DropdownMenuItem(text = { Text((if (k in types) "✓  " else "     ") + label) }, onClick = {
                                val n = if (k in types) types - k else types + k
                                if (n.isNotEmpty()) vm.change(null) { vm.types.value = n }
                            })
                        }
                        if (grid) {
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("网格列数:$columns(点击切换)") }, onClick = { vm.setColumns(if (columns >= 5) 3 else columns + 1) })
                        }
                    }
                }
            }
        }

        if (datePicker) {
            val st = rememberDatePickerState()
            DatePickerDialog(
                onDismissRequest = { datePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        val ms = st.selectedDateMillis
                        datePicker = false
                        if (ms != null) {
                            val d = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
                            scope.launch { if (!vm.jumpToDate(d)) android.widget.Toast.makeText(ctx, "这个日期附近没有文件", android.widget.Toast.LENGTH_SHORT).show() }
                        }
                    }) { Text("跳转") }
                },
                dismissButton = { TextButton(onClick = { datePicker = false }) { Text("取消") } },
            ) { DatePicker(state = st) }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().then(
                if (grid) Modifier.background(tg.bg)
                else if (LocalSettings.current.chatBackground == "plain") Modifier.background(tg.chatTop)
                else Modifier.background(Brush.verticalGradient(listOf(tg.chatTop, tg.chatBottom))),
            ),
        ) {
            val refresh = lazyItems.loadState.refresh
            when {
                !ready || (refresh is LoadState.Loading && lazyItems.itemCount == 0) -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                refresh is LoadState.Error && lazyItems.itemCount == 0 ->
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(refresh.error.message ?: "加载失败", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { lazyItems.retry() }) { Text("重试") }
                    }
                lazyItems.itemCount == 0 -> Text("这里没有符合条件的文件", Modifier.align(Alignment.Center), color = if (grid) tg.message else Color.White)
                // 聊天流 ↔ 网格:淡入 + 轻微缩放(Material fade through)
                else -> androidx.compose.animation.AnimatedContent(
                    targetState = grid, label = "chat-grid",
                    transitionSpec = {
                        if (motionStyle == Motion.OFF) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                        else (fadeIn(tween(220, 90, Motion.Standard)) + scaleIn(tween(300, easing = Motion.Standard), initialScale = 0.96f)) togetherWith fadeOut(tween(90))
                    },
                ) { g ->
                    if (g) MediaGrid(lazyItems, c.api, columns, gen, restoreId, restoreOffset, open, vm::anchorConsumed, vm::reportPosition, vm.freshOpen, vm::jumpToNewest)
                    else ChatFeed(lazyItems, c.api, gen, restoreId, restoreOffset, open, vm::anchorConsumed, vm::reportPosition, vm.freshOpen, vm::jumpToNewest)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 位置记忆

/**
 * 位置记忆:Pager 重建(含刚打开页面)后,等第一页到达,把列表滚回上次停留的条目与偏移;
 * 恢复完成后持续上报当前位置(去抖保存由 ViewModel 负责)。
 * 上报的"位置"= 屏幕上第一项:聊天流(反向布局)里是最靠下的一条,网格里是左上角那一格。
 */
@Composable
private fun PositionMemory(
    items: LazyPagingItems<Item>, gen: Int, restoreId: String?, restoreOffset: Int,
    firstIndex: () -> Int, firstOffset: () -> Int,
    scrollTo: suspend (Int, Int) -> Unit,
    onRestored: () -> Unit, onPosition: (String, Int, Boolean) -> Unit, fresh: Boolean,
) {
    var done by remember(gen) { mutableStateOf(restoreId == null) }
    // 切换视图/排序时,新数据到达前界面仍显示旧数据,必须先看到一次 Loading 再恢复;
    // 刚打开页面(fresh)时列表在第一页到达后才会组合,数据一定是新的,可直接恢复
    var sawLoading by remember(gen) { mutableStateOf(fresh) }
    val refresh = items.loadState.refresh
    LaunchedEffect(refresh) { if (refresh is LoadState.Loading) sawLoading = true }
    LaunchedEffect(gen, refresh, items.itemCount) {
        if (!done && sawLoading && refresh is LoadState.NotLoading && items.itemCount > 0) {
            val idx = items.itemSnapshotList.indexOfFirst { it?.id == restoreId }
            if (idx >= 0) scrollTo(idx, restoreOffset)
            done = true
            onRestored()
        }
    }
    LaunchedEffect(gen, done) {
        if (!done) return@LaunchedEffect
        snapshotFlow { firstIndex() to firstOffset() }.collect { (i, off) ->
            if (i in 0 until items.itemCount) {
                items.peek(i)?.let { onPosition(it.id, off, i == 0 && off < 40) } // 在最新一端 → 不记锚点,下次直接从最新开始
            }
        }
    }
}

// ---------------------------------------------------------------- 聊天流

@Composable
private fun ChatFeed(
    items: LazyPagingItems<Item>, api: Api, gen: Int, restoreId: String?, restoreOffset: Int,
    onOpen: (Item) -> Unit, onRestored: () -> Unit, onPosition: (String, Int, Boolean) -> Unit, fresh: Boolean, onJumpNewest: () -> Unit,
) {
    val state = rememberLazyListState()
    PositionMemory(items, gen, restoreId, restoreOffset, { state.firstVisibleItemIndex }, { state.firstVisibleItemScrollOffset },
        { i, o -> state.scrollToItem(i, o) }, onRestored, onPosition, fresh)
    var fast by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        var lastIdx = state.firstVisibleItemIndex
        var lastT = System.nanoTime()
        snapshotFlow { state.firstVisibleItemIndex }.collect { idx ->
            val now = System.nanoTime()
            val dt = (now - lastT) / 1e9
            if (dt >= 0.05) {
                fast = abs(idx - lastIdx) / dt > 12 // 每秒滑过超过 12 条
                lastIdx = idx; lastT = now
            }
        }
    }
    LaunchedEffect(state.isScrollInProgress) { if (!state.isScrollInProgress) fast = false }

    val cfg = LocalSettings.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 媒体气泡最大宽度约为屏幕的 72%(Telegram 约 0.7)
        val maxW = maxWidth * (LocalSettings.current.bubbleWidth / 100f)
        val maxH = maxWidth * 0.95f
        val endReached = items.loadState.append.endOfPaginationReached
        LazyColumn(
            state = state, reverseLayout = true, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(LocalSettings.current.chatSpacing.dp),
        ) {
            items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                val item = items[index] ?: return@items
                // 反向布局:索引大 = 更早 = 更靠上。当比上面一条晚一天(或已是最早的一条)时,在本条上方显示日期胶囊
                val older = if (index + 1 < items.itemCount) items.peek(index + 1) else null // 最后一项没有"更早的一条",peek 越界会闪退
                val showDate = if (older != null) localDate(older.takenAt) != localDate(item.takenAt) else endReached
                Column(Modifier.fillMaxWidth()) {
                    if (showDate && cfg.showDatePills) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Pill(dateLabel(localDate(item.takenAt)), sizeSp = 14, bold = true)
                        }
                    }
                    MediaBubble(item, api, maxW, maxH, loadEnabled = !fast || !cfg.pauseThumbsWhenFast, onClick = { onOpen(item) })
                }
            }
        }
        // 往上翻了一段以后,右下角出现"回到最新"按钮(Telegram 的做法)
        val scope = rememberCoroutineScope()
        JumpButton(
            visible = state.firstVisibleItemIndex > 6, up = false, modifier = Modifier.align(Alignment.BottomEnd),
        ) { onJumpNewest(); scope.launch { state.scrollToItem(0) } }
    }
}

/** 圆形悬浮按钮:聊天流里是向下的"回到最新",网格里是向上的"回到顶部"。 */
@Composable
private fun JumpButton(visible: Boolean, up: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tg = LocalTg.current
    androidx.compose.animation.AnimatedVisibility(
        visible, modifier.padding(end = 14.dp, bottom = 18.dp).navigationBarsPadding(),
        enter = androidx.compose.animation.fadeIn(tween(150)) + scaleIn(tween(200), initialScale = 0.6f),
        exit = fadeOut(tween(120)) + androidx.compose.animation.scaleOut(tween(150), targetScale = 0.6f),
    ) {
        Box(
            Modifier.size(44.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(tg.bubbleIn).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TgIcons.ChevronRight, if (up) "回到顶部" else "回到最新", tint = tg.message, modifier = Modifier.size(26.dp).rotate(if (up) -90f else 90f))
        }
    }
}

/** 无文字消息:图片/视频气泡(圆角、右下角时间、视频有播放键和时长);不可预览的格式显示成文件气泡。 */
@Composable
private fun MediaBubble(item: Item, api: Api, maxW: Dp, maxH: Dp, loadEnabled: Boolean, onClick: () -> Unit) {
    val tg = LocalTg.current
    val shape = RoundedCornerShape(topStart = 17.dp, topEnd = 17.dp, bottomEnd = 17.dp, bottomStart = 5.dp)
    if (!item.canShowNatively() || item.type == "audio" || item.type == "file") {
        Row(
            Modifier.padding(start = 8.dp).width(maxW).clip(shape).background(tg.bubbleIn).clickable(onClick = onClick).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(tg.accent), contentAlignment = Alignment.Center) {
                Icon(TgIcons.File, null, tint = Color.White)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(item.name, color = tg.bubbleText, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${formatSize(item.size)} · ${item.ext.uppercase()}", color = tg.date, fontSize = 13.sp)
            }
            Text(formatClock(item.takenAt), color = tg.date, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
        }
        return
    }
    val aspect = (item.aspect ?: (4f / 3f)).coerceIn(0.4f, 2.5f)
    var w = maxW
    var h = w / aspect
    if (h > maxH) { h = maxH; w = h * aspect }
    Box(Modifier.padding(start = 8.dp).width(w).height(h).mediaShared(item.id, shape).clip(shape).background(Color(0x33808080)).clickable(onClick = onClick)) {
        val url = if (item.isVideo) api.posterUrl(item) else api.imageUrl(item, LocalSettings.current.chatImageWidth)
        var failed by remember(item.id) { mutableStateOf(false) }
        LaunchedEffect(loadEnabled) { if (loadEnabled) failed = false } // 快速滑动时被暂停的请求不算失败
        if (item.brokenLabel() != null) {
            BrokenTile(item.brokenLabel()!! + (item.problem?.let { "\n点开查看原因" } ?: ""), Modifier.fillMaxSize())
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).memoryCacheKey(thumbKey(item))
                    .networkCachePolicy(if (loadEnabled) CachePolicy.ENABLED else CachePolicy.DISABLED).build(),
                contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                onError = { failed = true }, onSuccess = { failed = false },
            )
            if (failed && loadEnabled) BrokenTile("预览不可用\n(点开仍可尝试播放)", Modifier.fillMaxSize())
        }
        if (item.flags.truncated) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(50)).background(Color(0xCCFFB74D)).padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { Text("不完整", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
        }
        if (item.isVideo && item.brokenLabel() == null) {
            Box(Modifier.align(Alignment.Center).size(48.dp).clip(CircleShape).background(Color(0x80000000)), contentAlignment = Alignment.Center) {
                Icon(TgIcons.Play, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            formatDuration(item.durationMs).takeIf { it.isNotEmpty() && LocalSettings.current.showDuration }?.let { Pill(it, Modifier.align(Alignment.TopStart).padding(6.dp), sizeSp = 12) }
        }
    }
}

// ---------------------------------------------------------------- 网格

@Composable
private fun MediaGrid(
    items: LazyPagingItems<Item>, api: Api, columns: Int, gen: Int, restoreId: String?, restoreOffset: Int,
    onOpen: (Item) -> Unit, onRestored: () -> Unit, onPosition: (String, Int, Boolean) -> Unit, fresh: Boolean, onJumpNewest: () -> Unit,
) {
    val state = rememberLazyGridState()
    PositionMemory(items, gen, restoreId, restoreOffset, { state.firstVisibleItemIndex }, { state.firstVisibleItemScrollOffset },
        { i, o -> state.scrollToItem(i, o) }, onRestored, onPosition, fresh)
    var fast by remember { mutableStateOf(false) }
    LaunchedEffect(state, columns) {
        var lastIdx = state.firstVisibleItemIndex
        var lastT = System.nanoTime()
        snapshotFlow { state.firstVisibleItemIndex }.collect { idx ->
            val now = System.nanoTime()
            val dt = (now - lastT) / 1e9
            if (dt >= 0.05) {
                fast = abs(idx - lastIdx) / columns / dt > 10 // 每秒滑过超过 10 行
                lastIdx = idx; lastT = now
            }
        }
    }
    LaunchedEffect(state.isScrollInProgress) { if (!state.isScrollInProgress) fast = false }

    val gridScope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        state = state, columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(LocalSettings.current.gridSpacing.dp), verticalArrangement = Arrangement.spacedBy(LocalSettings.current.gridSpacing.dp),
    ) {
        items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
            val item = items[index]
            if (item != null) {
                MediaThumb(item, api, loadEnabled = !fast || !LocalSettings.current.pauseThumbsWhenFast, modifier = Modifier.aspectRatio(1f).mediaShared(item.id, androidx.compose.ui.graphics.RectangleShape).clickable { onOpen(item) })
            } else {
                Box(Modifier.aspectRatio(1f))
            }
        }
    }
    JumpButton(visible = state.firstVisibleItemIndex > columns * 5, up = true, modifier = Modifier.align(Alignment.BottomEnd)) {
        onJumpNewest(); gridScope.launch { state.scrollToItem(0) }
    }
    }
}

// ---------------------------------------------------------------- 工具

private fun localDate(rfc: String): LocalDate = runCatching {
    OffsetDateTime.parse(rfc).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
}.getOrDefault(LocalDate.MIN)

private fun dateLabel(d: LocalDate): String {
    val now = LocalDate.now()
    return when {
        d == LocalDate.MIN -> "未知日期"
        d == now -> "今天"
        d == now.minusDays(1) -> "昨天"
        d.year == now.year -> "${d.monthValue}月${d.dayOfMonth}日"
        else -> "${d.year}年${d.monthValue}月${d.dayOfMonth}日"
    }
}

private fun formatClock(rfc: String): String = runCatching {
    OffsetDateTime.parse(rfc).atZoneSameInstant(ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
}.getOrDefault("")
