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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localtg.AppContainer
import com.localtg.AppLog
import com.localtg.data.Item
import com.localtg.data.ViewerFeed
import com.localtg.data.friendlyError
import com.localtg.ui.tg.BarIcon
import com.localtg.ui.tg.LocalTg
import com.localtg.ui.tg.TgBar
import com.localtg.ui.tg.TgGlassScaffold
import com.localtg.ui.tg.barContentPadding
import androidx.compose.foundation.layout.Spacer
import com.localtg.ui.tg.TgIcons
import kotlinx.coroutines.delay

/** 全局搜索:按文件名搜图片 / 视频,结果是缩略图网格,点开进查看器,翻到末尾自动加载更多。 */
@Composable
fun SearchScreen(c: AppContainer, onBack: (() -> Unit)?, onOpenViewer: (Int) -> Unit) {
    val tg = LocalTg.current
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") } // 在底栏标签之间切换后还在
    var typeTab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) } // 0 全部 / 1 照片 / 2 视频
    var results by remember { mutableStateOf<List<Item>>(emptyList()) }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    val types = when (typeTab) { 1 -> "photo,gif"; 2 -> "video"; else -> "photo,video,gif" }
    val focus = remember { FocusRequester() }
    val fm = LocalFocusManager.current

    LaunchedEffect(Unit) { focus.requestFocus() }
    // 输入停顿 350ms 后自动搜索
    LaunchedEffect(query, types) {
        val q = query.trim()
        if (q.isEmpty()) { results = emptyList(); nextCursor = null; searched = false; error = null; return@LaunchedEffect }
        delay(350)
        loading = true; error = null
        runCatching { c.api.search(q, types, null) }
            .onSuccess { results = it.items; nextCursor = it.nextCursor; searched = true; AppLog.i("search", "“$q” → ${it.items.size} 条") }
            .onFailure { error = friendlyError(it); AppLog.w("search", "搜索失败 q=$q", it) }
        loading = false
    }

    TgGlassScaffold(bar = {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) { // 顶栏内容高 56dp(Telegram)
                if (onBack != null) BarIcon(TgIcons.Back, "返回") { fm.clearFocus(); onBack() } else Spacer(Modifier.width(16.dp))
                // Telegram 新版的搜索框:顶栏里的圆角胶囊
                Box(Modifier.weight(1f).padding(end = 8.dp).clip(RoundedCornerShape(50)).background(if (tg.dark) androidx.compose.ui.graphics.Color(0x14FFFFFF) else tg.gray).padding(horizontal = 16.dp, vertical = 9.dp)) {
                    if (query.isEmpty()) Text("搜索文件名", color = tg.barSub, fontSize = 17.sp)
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = androidx.compose.material3.LocalTextStyle.current.merge(TextStyle(color = tg.barText, fontSize = 17.sp)),
                        cursorBrush = SolidColor(tg.barText),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { fm.clearFocus() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (query.isNotEmpty()) BarIcon(TgIcons.Close, "清除") { query = "" }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("全部", "照片", "视频").forEachIndexed { i, label ->
                    Box(Modifier.hapticClickable(Hap.Tick) { typeTab = i }.padding(horizontal = 16.dp).height(40.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(label, color = if (typeTab == i) tg.barActive else tg.barTabIdle, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Box(Modifier.padding(top = 6.dp).height(3.dp).width(28.dp).clip(RoundedCornerShape(2.dp)).background(if (typeTab == i) tg.barActive else Color.Transparent))
                        }
                    }
                }
            }
    }) {
        Box(Modifier.fillMaxSize().imePadding()) {
            when {
                query.isBlank() -> Hint("输入文件名的一部分,例如 IMG_0398", tg.message)
                error != null -> Hint(error!!, tg.danger)
                loading && results.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                searched && results.isEmpty() -> Hint("没有找到匹配的文件", tg.message)
                else -> {
                    val grid = rememberLazyGridState()
                    // 滚到接近底部时加载下一页
                    val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    LaunchedEffect(last, results.size, nextCursor) {
                        val cur = nextCursor
                        if (cur != null && !loading && last >= results.size - 12) {
                            loading = true
                            runCatching { c.api.search(query.trim(), types, cur) }
                                .onSuccess { r -> results = results + r.items.filter { n -> results.none { it.id == n.id } }; nextCursor = r.nextCursor }
                                .onFailure { nextCursor = null }
                            loading = false
                        }
                    }
                    LazyVerticalGrid(
                        state = grid, columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(), contentPadding = barContentPadding(),
                        horizontalArrangement = Arrangement.spacedBy(1.dp), verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        items(results, key = { it.id }) { item ->
                            val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            MediaThumb(
                                item, c.api,
                                modifier = Modifier.aspectRatio(1f).heroSource(item.id).pressScale(src, 0.04f).hapticClickable(src) {
                                    fm.clearFocus()
                                    val snapshot = results
                                    val q = query.trim()
                                    var firstCursor = nextCursor
                                    val sequence = com.localtg.data.SlideSequence(snapshot) {
                                        val use = firstCursor
                                        if (use == null) emptyList<Item>() to false else {
                                            val r = c.api.search(q, types, use)
                                            firstCursor = r.nextCursor
                                            r.items to (firstCursor != null)
                                        }
                                    }
                                    val source = com.localtg.data.SlideSource(com.localtg.data.SlideScope(types, search = q), sequence = sequence)
                                    c.viewerFeed = ViewerFeed(snapshot, slideSource = source) { _, _ ->
                                        val (page, more) = sequence.more(); page to if (more) "more" else null
                                    }
                                    onOpenViewer(snapshot.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun Hint(text: String, color: Color) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(text, color = color, fontSize = 15.sp) }
}
