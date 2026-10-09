package com.localtg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localtg.AppContainer
import com.localtg.AppLog
import com.localtg.data.ApiException
import com.localtg.data.Item
import com.localtg.data.ViewerFeed
import com.localtg.data.friendlyError
import com.localtg.ui.tg.BarIcon
import com.localtg.ui.tg.LocalTg
import com.localtg.ui.tg.TgBar
import com.localtg.ui.tg.TgIcons
import kotlinx.coroutines.launch

/** 服务端每个范围 × 类型预先准备了 12 份随机序列,种子 1..12 就是用哪一份。换序列 = 换成另一份(不会换成当前这份)。 */
private const val RANDOM_SLOTS = 12

private fun newRandomSeed(cur: Long): Long {
    val slot = if (cur <= 0) -1 else ((cur - 1) % RANDOM_SLOTS).toInt()
    var n = kotlin.random.Random.nextInt(RANDOM_SLOTS - (if (slot >= 0) 1 else 0))
    if (slot >= 0 && n >= slot) n++
    return n + 1L
}

/**
 * 列表状态放在 ViewModel 里(随导航栈上的这一页保留):点开媒体进查看器时这一页的 composition 会被销毁,返回时重建 ——
 * 如果状态只在 remember 里,返回后列表是空的,重新取第一批并滚到顶部,位置就丢了。
 */
class RandomViewModel : ViewModel() {
    var items by mutableStateOf<List<Item>>(emptyList())
    var batches = 0           // 已经取了几批(下一批的批号)
    var loadedKey: String? = null // items 是按哪个 (类型, 范围, 序列) 取的;不变就不用重新取
    var viewerNext = 0        // 查看器接着往后取的批号
}

/** 随机浏览:一个特殊的群组,从媒体库里随机取图片 / 视频(label / roots 不为空 = 只随机这个盘符里的,否则随机所有盘符)(可选「全部 / 图片 / 视频」),滚到底自动再取一批,点右上角重新洗牌。 */
@Composable
fun RandomScreen(c: AppContainer, label: String?, roots: String?, onBack: () -> Unit, onOpenViewer: (Int) -> Unit) {
    val tg = LocalTg.current
    val st by c.settings.state.collectAsState()
    val tab = st.randomType.coerceIn(0, 2)
    val types = when (tab) { 1 -> "photo,gif"; 2 -> "video"; else -> "photo,video,gif" }
    val vm: RandomViewModel = viewModel()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) } // 出错后点「重试」加 1
    val seed = st.randomSeed // 随机种子存在设置里:不点「重新洗牌」,每次进来、切类型再切回来都是同一个随机序列
    LaunchedEffect(Unit) { if (c.settings.value.randomSeed == 0L) c.settings.update { copy(randomSeed = newRandomSeed(randomSeed)) } }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    fun explain(e: Throwable) =
        if (e is ApiException && e.http == 404) "服务端版本太旧,不支持随机浏览。请把服务端更新到 1.7.0 或更新的版本。" else friendlyError(e)

    // gen:每次整批重来(换类型 / 重新洗牌)加 1。旧的请求被取消后,它收尾时不能去动新请求的状态(loading / error)。
    val gen = remember { intArrayOf(0) }

    suspend fun more(replace: Boolean) {
        if (replace) gen[0]++ else if (loading) return
        val my = gen[0]
        loading = true; error = null
        try {
            val batch = if (replace) 0 else vm.batches
            val r = c.api.random(types, 60, roots, seed, batch)
            if (my != gen[0]) return
            vm.batches = batch + 1
            val known = if (replace) HashSet() else vm.items.mapTo(HashSet()) { it.id }
            val fresh = r.items.filter { known.add(it.id) }
            vm.items = if (replace) fresh else vm.items + fresh
            AppLog.i("random", "取到 ${fresh.size} 条(类型 $types,共 ${vm.items.size})")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 离开页面 / 换类型导致的取消不是错误
        } catch (e: Exception) {
            if (my == gen[0]) { error = explain(e); AppLog.w("random", "随机浏览失败", e) }
        } finally {
            if (my == gen[0]) loading = false
        }
    }

    // 换类型 / 点「重新洗牌」(换了 seed) / 重试 → 从第 0 批重新取并回到顶部
    LaunchedEffect(types, roots, seed, retry) {
        if (seed == 0L) return@LaunchedEffect // 第一次进来 seed 还没生成,等它生成后再取
        val key = "$types|$roots|$seed"
        if (vm.loadedKey == key && vm.items.isNotEmpty()) return@LaunchedEffect // 从查看器返回:列表还在,位置由 rememberLazyGridState 恢复
        val firstLoad = vm.loadedKey == null
        vm.loadedKey = key
        vm.items = emptyList()
        more(replace = true)
        if (vm.items.isEmpty()) vm.loadedKey = null // 没取到(出错 / 被取消):下次重新取
        else if (!firstLoad) grid.scrollToItem(0)
    }
    // 从查看器返回:列表定位到查看器里最后停留的那一项(查看器自己往后多取的几批也并进列表)
    val ret by c.viewerReturn.collectAsState()
    LaunchedEffect(ret, vm.items.size) {
        val id = ret?.takeIf { it.first == "random" }?.second ?: return@LaunchedEffect
        if (vm.items.isEmpty()) return@LaunchedEffect
        var idx = vm.items.indexOfFirst { it.id == id }
        if (idx < 0) {
            val known = vm.items.mapTo(HashSet()) { it.id }
            vm.items = vm.items + c.viewerFeed.items.filter { known.add(it.id) }
            vm.batches = maxOf(vm.batches, vm.viewerNext)
            idx = vm.items.indexOfFirst { it.id == id }
        }
        if (idx >= 0) {
            androidx.compose.runtime.withFrameNanos { } // 等列表量好一次再判断可见性
            if (grid.layoutInfo.visibleItemsInfo.none { it.index == idx }) grid.scrollToItem(idx)
        }
        c.viewerReturn.value = null
    }
    // 滚到接近底部,再取一批
    val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
    LaunchedEffect(last, vm.items.size) {
        if (vm.items.isNotEmpty() && !loading && error == null && last >= vm.items.size - 15) more(replace = false)
    }

    Column(Modifier.fillMaxSize()) {
        TgBar {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                BarIcon(TgIcons.Back, "返回", onBack)
                Text(if (label != null) "随机浏览 · $label" else "随机浏览", Modifier.weight(1f).padding(start = 4.dp), color = tg.barText, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                BarIcon(TgIcons.Refresh, "重新洗牌") { c.settings.update { copy(randomSeed = newRandomSeed(randomSeed)) } }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("全部", "图片", "视频").forEachIndexed { i, label ->
                    Box(
                        Modifier.hapticClickable(Hap.Tick) { c.settings.update { copy(randomType = i) } }.padding(horizontal = 16.dp).height(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(label, color = if (tab == i) tg.barText else tg.barTabIdle, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Box(
                                Modifier.padding(top = 6.dp).height(3.dp).width(28.dp).clip(RoundedCornerShape(2.dp))
                                    .background(if (tab == i) tg.barText else Color.Transparent),
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                vm.items.isEmpty() && loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                vm.items.isEmpty() && error != null -> Column(
                    Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error!!, color = tg.danger, fontSize = 15.sp)
                    TextButton(onClick = { vm.loadedKey = null; retry++ }) { Text("重试") }
                }
                vm.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("没有可显示的文件", color = tg.message, fontSize = 15.sp)
                }
                else -> LazyVerticalGrid(
                    state = grid, columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(1.dp), verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    items(vm.items, key = { it.id }) { item ->
                        MediaThumb(
                            item, c.api,
                            modifier = Modifier.aspectRatio(1f).mediaShared(item.id, RectangleShape).hapticClickable {
                                val snap = vm.items
                                // 查看器翻到末尾接着取固定序列的下一批;游标只表示"还有",内容去重由 ViewerFeed 处理
                                vm.viewerNext = vm.batches
                                c.viewerReturn.value = null
                                c.viewerFeed = ViewerFeed(snap, originDialogId = "random") { _, _ -> c.api.random(types, 60, roots, seed, vm.viewerNext++).items to "more" }
                                onOpenViewer(snap.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
                            },
                        )
                    }
                }
            }
            if (error != null && vm.items.isNotEmpty()) {
                Text(
                    error!!,
                    Modifier.align(Alignment.BottomCenter).padding(12.dp).background(tg.bar, RoundedCornerShape(8.dp))
                        .clickable { scope.launch { more(false) } }.padding(10.dp),
                    color = tg.barText, fontSize = 13.sp,
                )
            }
        }
    }
}
