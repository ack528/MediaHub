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

/** 随机浏览:一个特殊的群组,从媒体库里随机取图片 / 视频(label / roots 不为空 = 只随机这个盘符里的,否则随机所有盘符)(可选「全部 / 图片 / 视频」),滚到底自动再取一批,点右上角重新洗牌。 */
@Composable
fun RandomScreen(c: AppContainer, label: String?, roots: String?, onBack: () -> Unit, onOpenViewer: (Int) -> Unit) {
    val tg = LocalTg.current
    val st by c.settings.state.collectAsState()
    val tab = st.randomType.coerceIn(0, 2)
    val types = when (tab) { 1 -> "photo,gif"; 2 -> "video"; else -> "photo,video,gif" }
    var items by remember { mutableStateOf<List<Item>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) } // 每点一次「重新洗牌」加 1
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    fun explain(e: Throwable) =
        if (e is ApiException && e.http == 404) "服务端版本太旧,不支持随机浏览。请把服务端更新到 1.6.0 或更新的版本。" else friendlyError(e)

    // gen:每次整批重来(换类型 / 重新洗牌)加 1。旧的请求被取消后,它收尾时不能去动新请求的状态(loading / error)。
    val gen = remember { intArrayOf(0) }

    suspend fun more(replace: Boolean) {
        if (replace) gen[0]++ else if (loading) return
        val my = gen[0]
        loading = true; error = null
        try {
            val r = c.api.random(types, 60, roots)
            if (my != gen[0]) return
            val known = if (replace) HashSet() else items.mapTo(HashSet()) { it.id }
            val fresh = r.items.filter { known.add(it.id) }
            items = if (replace) fresh else items + fresh
            AppLog.i("random", "取到 ${fresh.size} 条(类型 $types,共 ${items.size})")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 离开页面 / 换类型导致的取消不是错误
        } catch (e: Exception) {
            if (my == gen[0]) { error = explain(e); AppLog.w("random", "随机浏览失败", e) }
        } finally {
            if (my == gen[0]) loading = false
        }
    }

    // 换类型 / 重新洗牌 → 整个换一批并回到顶部
    LaunchedEffect(types, round, roots) {
        items = emptyList()
        more(replace = true)
        if (items.isNotEmpty()) grid.scrollToItem(0)
    }
    // 滚到接近底部,再取一批
    val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
    LaunchedEffect(last, items.size) {
        if (items.isNotEmpty() && !loading && error == null && last >= items.size - 15) more(replace = false)
    }

    Column(Modifier.fillMaxSize()) {
        TgBar {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                BarIcon(TgIcons.Back, "返回", onBack)
                Text(if (label != null) "随机浏览 · $label" else "随机浏览", Modifier.weight(1f).padding(start = 4.dp), color = tg.barText, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                BarIcon(TgIcons.Refresh, "重新洗牌") { round++ }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("全部", "图片", "视频").forEachIndexed { i, label ->
                    Box(
                        Modifier.clickable { c.settings.update { copy(randomType = i) } }.padding(horizontal = 16.dp).height(40.dp),
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
                items.isEmpty() && loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                items.isEmpty() && error != null -> Column(
                    Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error!!, color = tg.danger, fontSize = 15.sp)
                    TextButton(onClick = { round++ }) { Text("重试") }
                }
                items.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("没有可显示的文件", color = tg.message, fontSize = 15.sp)
                }
                else -> LazyVerticalGrid(
                    state = grid, columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(1.dp), verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        MediaThumb(
                            item, c.api,
                            modifier = Modifier.aspectRatio(1f).mediaShared(item.id, RectangleShape).clickable {
                                val snap = items
                                // 查看器翻到末尾继续随机取;游标只表示"还有",内容去重由 ViewerFeed 处理
                                c.viewerFeed = ViewerFeed(snap) { _, _ -> c.api.random(types, 60, roots).items to "more" }
                                onOpenViewer(snap.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
                            },
                        )
                    }
                }
            }
            if (error != null && items.isNotEmpty()) {
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
