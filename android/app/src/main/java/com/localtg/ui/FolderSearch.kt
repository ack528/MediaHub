package com.localtg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localtg.AppContainer
import com.localtg.AppLog
import com.localtg.data.Item
import com.localtg.data.friendlyError
import com.localtg.ui.tg.BarIcon
import com.localtg.ui.tg.LocalTg
import com.localtg.ui.tg.TgIcons
import kotlinx.coroutines.delay

/** 文件夹内搜索的顶栏:返回 | 输入框 | 清除。 */
@Composable
fun FolderSearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val tg = LocalTg.current
    val focus = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        BarIcon(TgIcons.Back, "关闭搜索") { fm.clearFocus(); onClose() }
        Box(Modifier.weight(1f).padding(end = 4.dp)) {
            if (query.isEmpty()) Text("在这个文件夹里搜索文件名", color = tg.barSub, fontSize = 17.sp)
            BasicTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                textStyle = TextStyle(color = tg.barText, fontSize = 17.sp),
                cursorBrush = SolidColor(tg.barText),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { fm.clearFocus() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (query.isNotEmpty()) BarIcon(TgIcons.Close, "清除") { onQuery("") }
    }
}

/**
 * 文件夹内搜索的结果:盖在列表上面,按文件名搜(只搜当前显示的类型),点一条就关闭搜索、列表直接定位到那条文件。
 * 翻到接近底部自动加载下一页。
 */
@Composable
fun FolderSearchPanel(c: AppContainer, dialogId: String, query: String, types: String, picking: Boolean = false, onPick: (Item) -> Unit) {
    val tg = LocalTg.current
    val fm = LocalFocusManager.current
    var results by remember { mutableStateOf<List<Item>>(emptyList()) }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }

    LaunchedEffect(query, types) {
        val q = query.trim()
        if (q.isEmpty()) { results = emptyList(); nextCursor = null; searched = false; error = null; return@LaunchedEffect }
        delay(300)
        loading = true; error = null
        runCatching { c.api.search(q, types, null, dialog = dialogId) }
            .onSuccess { results = it.items; nextCursor = it.nextCursor; searched = true; AppLog.i("search", "文件夹内 “$q” → ${it.items.size} 条") }
            .onFailure { error = friendlyError(it); AppLog.w("search", "文件夹内搜索失败 q=$q", it) }
        loading = false
    }

    Box(Modifier.fillMaxSize().background(tg.bg).imePadding()) {
        val dim by androidx.compose.animation.core.animateFloatAsState(if (picking) 0.45f else 1f, androidx.compose.animation.core.tween(160), label = "pick-dim")
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim }) {
        when {
            query.isBlank() -> Hint("输入文件名的一部分,点结果直接跳到那条文件", tg.message)
            error != null -> Hint(error!!, tg.danger)
            loading && results.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            searched && results.isEmpty() -> Hint("这个文件夹里没有匹配的文件", tg.message)
            else -> {
                val state = rememberLazyListState()
                val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                LaunchedEffect(last, results.size, nextCursor) {
                    val cur = nextCursor
                    if (cur != null && !loading && last >= results.size - 8) {
                        loading = true
                        runCatching { c.api.search(query.trim(), types, cur, dialog = dialogId) }
                            .onSuccess { r -> results = results + r.items.filter { n -> results.none { it.id == n.id } }; nextCursor = r.nextCursor }
                            .onFailure { nextCursor = null }
                        loading = false
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), state = state) {
                    items(results, key = { it.id }) { item ->
                        Row(
                            Modifier.fillMaxWidth().hapticClickable(Hap.Tick) { if (!picking) { fm.clearFocus(); onPick(item) } }.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            MediaThumb(item, c.api, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)), showName = false)
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = tg.name, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(item.takenAt.replace('T', ' ').take(16), color = tg.message, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        }
        if (picking) CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), strokeWidth = 3.dp)
    }
}

@Composable
private fun Hint(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(text, color = color, fontSize = 15.sp) }
}
