package com.localtg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import com.localtg.AppContainer
import com.localtg.data.Dialog
import com.localtg.data.friendlyError
import com.localtg.ui.tg.BarIcon
import com.localtg.ui.tg.LocalTg
import com.localtg.ui.tg.TgAvatar
import com.localtg.ui.tg.TgBar
import com.localtg.ui.tg.TgGlassScaffold
import com.localtg.ui.tg.barContentPadding
import com.localtg.ui.tg.TgIcons
import com.localtg.ui.tg.toItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class DialogsViewModel(private val c: AppContainer) : ViewModel() {
    private val ns = c.session.ns()
    val recentNames = c.viewState.recentNames(ns)
    val dialogs = MutableStateFlow<List<Dialog>>(emptyList())
    val loading = MutableStateFlow(true)
    val error = MutableStateFlow<String?>(null)

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true; error.value = null
            runCatching {
                var cursor: String? = null
                val all = mutableListOf<Dialog>()
                do {
                    val r = c.api.dialogs(cursor = cursor, limit = 100, onCached = { cached ->
                        // 先显示上次的列表(只对第一页、且网络结果还没来):打开应用 / 切回来时不用等网络
                        if (cursor == null && all.isEmpty() && dialogs.value.isEmpty()) {
                            dialogs.value = cached.dialogs
                            c.dialogCache = c.dialogCache + cached.dialogs.associateBy { it.id }
                            loading.value = false
                        }
                    })
                    all += r.dialogs
                    dialogs.value = all.toList() // 每取到一页就更新,列表渐进出现
                    c.dialogCache = c.dialogCache + r.dialogs.associateBy { it.id } // 缓存也同步更新:加载没完就点进去,标题也要能取到
                    cursor = r.nextCursor
                } while (cursor != null)
            }.onFailure { error.value = friendlyError(it) }
            c.dialogCache = dialogs.value.associateBy { it.id }
            loading.value = false
        }
    }
}

/** 聊天列表:一个母文件夹 = 一个群。布局取自 Telegram DialogCell:行高 70dp、头像 52dp、文字起点 72dp。左上角三条杠展开侧边栏。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DialogsScreen(
    c: AppContainer, onOpen: (Dialog, androidx.compose.ui.geometry.Rect?) -> Unit, onSettings: () -> Unit, onSearch: () -> Unit,
    onRandom: (String?, String?, androidx.compose.ui.geometry.Rect?) -> Unit, onSection: (String) -> Unit,
    onDrawer: (Boolean) -> Unit = {},
) {
    val activeId by c.session.activeId.collectAsState()
    // 每台服务器一个独立的列表(切换服务器 = 换一个 ViewModel,重新加载)
    val vm: DialogsViewModel = viewModel(key = "dialogs-$activeId", factory = viewModelFactory { initializer { DialogsViewModel(c) } })
    val all by vm.dialogs.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val recentNames by vm.recentNames.collectAsState(initial = emptyMap())
    val scope = rememberCoroutineScope()
    val tg = LocalTg.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val hap = LocalHaptics.current
    val ptr = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()

    // 盘符 = Telegram 的聊天分组标签;主界面左右滑动切换盘符(页 0 = 全部,后面每个盘符一页)
    val labels = remember(all) { all.map { it.rootLabel }.distinct().sorted() }
    val st = com.localtg.ui.tg.LocalSettings.current
    val pageCount = if (labels.size > 1) labels.size + 1 else 1
    val pager = androidx.compose.foundation.pager.rememberPagerState { pageCount }
    LaunchedEffect(activeId) { pager.scrollToPage(0) } // 换服务器回到"全部"
    fun listFor(t: Int): List<Dialog> {
        val inTab = if (t == 0 || t > labels.size) all else all.filter { it.rootLabel == labels[t - 1] }
        val f = if (st.dialogMinCount > 0) inTab.filter { it.mediaCount >= st.dialogMinCount } else inTab
        return when (st.dialogSort) {
            "name" -> f.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            "path" -> f.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.pathDisplay })
            "count" -> f.sortedByDescending { it.mediaCount }
            else -> f
        }
    }

        TgGlassScaffold(bar = {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) { // 顶栏内容高 56dp(Telegram)
                    // 左上角不再有菜单键:原来侧边栏里的功能都在「设置」里(服务器管理、随机序列、日志、关于、退出登录)
                    Text("本地浏览", Modifier.weight(1f).padding(start = 20.dp), color = tg.barText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    BarIcon(TgIcons.Search, "搜索", onSearch)
                }
                if (labels.size > 1) FolderTabs(labels, pager) { scope.launch { pager.animateScrollToPage(it) } }
        }) {

            // 下拉刷新
            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = loading && all.isNotEmpty(), onRefresh = { hap.perform(Hap.Long); vm.refresh() }, modifier = Modifier.fillMaxSize(), state = ptr,
                // 下拉刷新的圆圈出现在玻璃顶栏下面,不被它挡住
                indicator = {
                    androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                        state = ptr, isRefreshing = loading && all.isNotEmpty(),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = com.localtg.ui.tg.LocalBarInsets.current.calculateTopPadding()),
                    )
                },
            ) {
                androidx.compose.foundation.pager.HorizontalPager(
                    pager, Modifier.fillMaxSize(), userScrollEnabled = st.swipeGroups && pageCount > 1, beyondViewportPageCount = 0,
                ) { page ->
                    val shown = remember(all, page, labels, st.dialogSort, st.dialogMinCount) { listFor(page) }
                    when {
                        shown.isEmpty() && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        shown.isEmpty() && error != null -> Column(
                            Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(error!!, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { vm.refresh() }) { Text("重试") }
                        }
                        shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("没有可显示的文件夹", color = tg.message)
                        }
                        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = barContentPadding()) {
                            // 置顶的特殊群组:「全部」页随机所有盘符,各盘符页只随机这个盘符
                            item(key = "random") {
                                val lb = if (page == 0 || page > labels.size) null else labels[page - 1]
                                RandomRow(lb) { r -> onRandom(lb, lb?.let { l -> all.filter { it.rootLabel == l }.map { it.rootId }.distinct().joinToString(",") }, r) }
                            }
                            itemsIndexed(shown, key = { _, d -> d.id }) { i, d ->
                                Box(Modifier.animateItem()) { DialogRow(d, c, recentNames[d.id], onClick = { r -> c.chatSiblings = shown.map { it.id }; onOpen(d, r) }) }
                            }
                        }
                    }
                }
            }
        }

}

/**
 * 盘符标签栏。细节动效开着时,下划线是**同一条**在标签之间滑动(onetake 的"有东西活下来"):位置由分页器的连续位置插值,
 * 手指横滑页面时它跟着走,点标签时随分页器的动画滑过去;关着时每个标签自己画一条(原来的样子)。
 */
@Composable
private fun FolderTabs(labels: List<String>, pager: androidx.compose.foundation.pager.PagerState, onPick: (Int) -> Unit) {
    val tg = LocalTg.current
    val fine = fineMotionOn()
    val pos = remember { androidx.compose.runtime.mutableStateMapOf<Int, Pair<Float, Float>>() }
    val count = labels.size + 1
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Row {
            (listOf("全部") + labels).forEachIndexed { i, l ->
                FolderTab(
                    l, pager.currentPage == i, underline = !fine,
                    Modifier.reportPlacement { x, w -> pos[i] = x to w },
                ) { onPick(i) }
            }
        }
        if (fine) {
            Box(
                Modifier.align(Alignment.BottomStart)
                    .layout { m, _ ->
                        val ind = tabIndicator(pager.currentPage + pager.currentPageOffsetFraction, pos, count)
                        val x = ind?.first?.roundToInt() ?: 0
                        val w = ind?.second?.roundToInt() ?: 0
                        val p = m.measure(Constraints.fixed(w, 3.dp.roundToPx()))
                        layout(x + w, p.height) { p.place(x, 0) }
                    }
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(tg.barActive),
            )
        }
    }
}

@Composable
private fun FolderTab(text: String, selected: Boolean, underline: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tg = LocalTg.current
    val color by androidx.compose.animation.animateColorAsState(
        if (selected) tg.barActive else tg.barTabIdle, androidx.compose.animation.core.tween(if (fineMotionOn()) 160 else 0), label = "tabColor",
    )
    Column(modifier.height(44.dp).hapticClickable(Hap.Tick, onClick).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Box(
            Modifier.height(3.dp).fillMaxWidth().clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                .background(if (underline && selected) tg.barActive else Color.Transparent),
        )
    }
}

/** 量出标签在行里的位置(左边, 宽度),给 [FolderTabs] 的指示条用。 */
private fun Modifier.reportPlacement(report: (Float, Float) -> Unit): Modifier =
    this.onGloballyPositioned { report(it.positionInParent().x, it.size.width.toFloat()) }

/** 置顶的特殊群组「随机浏览」:不是文件夹,点开后从整个库里随机取图片 / 视频。样式和普通群的行一致。 */
@Composable
private fun RandomRow(label: String?, onClick: (androidx.compose.ui.geometry.Rect?) -> Unit) {
    val tg = LocalTg.current
    val at = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) } // 这一行的位置:点进去时从这里展开
    Box(Modifier.fillMaxWidth().height(70.dp).onGloballyPositioned { at[0] = it.boundsInRoot() }.hapticClickable { onClick(at[0]) }) {
        // 随机浏览的头像:紫色渐变圆 + 白色随机图标(和设置首页的彩色图标、底栏的「随机」图标同一套)
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.localtg.R.drawable.ic_random), null,
            Modifier.padding(start = 11.dp, top = 9.dp).size(52.dp),
        )
        Column(Modifier.fillMaxSize().padding(start = 72.dp, end = 12.dp, top = 10.dp)) {
            Text(if (label != null) "随机浏览 · $label" else "随机浏览", color = tg.name, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(if (label != null) "只随机 $label 里的图片或视频" else "随机所有盘符里的图片或视频", Modifier.padding(top = 3.dp), color = tg.message, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DialogRow(d: Dialog, c: AppContainer, recentName: String?, onClick: (androidx.compose.ui.geometry.Rect?) -> Unit) {
    val tg = LocalTg.current
    val at = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) } // 这一行的位置:点进去时从这里展开
    Box(Modifier.fillMaxWidth().height(70.dp).onGloballyPositioned { at[0] = it.boundsInRoot() }.hapticClickable { onClick(at[0]) }) {
        Box(Modifier.padding(start = 11.dp, top = 9.dp)) {
            TgAvatar(d.title, d.id, 52.dp, d.last?.toItem(d.id), c.api)
        }
        Column(Modifier.fillMaxSize().padding(start = 72.dp, end = 12.dp, top = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    d.title, Modifier.weight(1f), color = tg.name, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(d.last?.takenAt?.let { formatDialogTime(it) }.orEmpty(), color = tg.date, fontSize = 13.sp)
            }
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                val sub = com.localtg.ui.tg.LocalSettings.current.dialogSubtitle
                if (sub == "path") {
                    Text(d.pathDisplay, Modifier.weight(1f), color = tg.message, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else if (sub == "none") {
                    Box(Modifier.weight(1f))
                } else {
                    Text(
                        recentName ?: "暂无浏览记录",
                        Modifier.weight(1f), color = tg.message, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                // 数量:相当于"已静音群"的灰色计数(Telegram 的未读计数:高 23dp、全圆角、13sp 中等粗细)
                Box(
                    Modifier.padding(start = 6.dp).defaultMinSize(minWidth = 23.dp, minHeight = 23.dp).clip(RoundedCornerShape(12.dp))
                        .background(tg.badgeMuted).padding(horizontal = 7.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("${d.mediaCount}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
        }
        Box(Modifier.align(Alignment.BottomStart).padding(start = 72.dp).fillMaxWidth().height(1.dp).background(tg.divider))
    }
}

/** 当天显示时间,今年内显示月日,更早显示年月日(Telegram 的做法)。 */
fun formatDialogTime(rfc: String): String = runCatching {
    val t = java.time.OffsetDateTime.parse(rfc).atZoneSameInstant(java.time.ZoneId.systemDefault())
    val now = java.time.ZonedDateTime.now()
    when {
        t.toLocalDate() == now.toLocalDate() -> t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        t.year == now.year -> t.format(java.time.format.DateTimeFormatter.ofPattern("M月d日"))
        else -> t.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/M/d"))
    }
}.getOrDefault("")
