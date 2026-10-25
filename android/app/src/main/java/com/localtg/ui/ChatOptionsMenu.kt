package com.localtg.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localtg.ui.tg.LocalTg
import com.localtg.ui.tg.TgIcons

/** One popup, three first-level rows. Second-level options expand in place. */
@Composable
internal fun ChatOptionsMenu(sort: String, asc: Boolean, types: Set<String>, mode: Int, columns: Int,
    onSort: (String) -> Unit, onDirection: (Boolean) -> Unit, onType: (String) -> Unit,
    onMode: (Int) -> Unit, onColumns: (Int) -> Unit, onDate: () -> Unit,
) {
    var expanded by remember { mutableStateOf<String?>(null) }
    val tg = LocalTg.current
    val sorts = listOf("taken" to "拍摄时间", "modified" to "修改时间", "created" to "创建时间", "name" to "名称", "size" to "大小", "type" to "文件类型")
    val media = listOf("photo" to "图片", "video" to "视频", "gif" to "GIF")
    val modes = listOf("聊天流", "网格", "瀑布流")
    @Composable fun Header(key: String, title: String, value: String) {
        val rotation by animateFloatAsState(if (expanded == key) 90f else 0f)
        DropdownMenuItem(text = {
            Row(Modifier.fillMaxWidth()) {
                Text(title, color = tg.name, modifier = Modifier.weight(1f))
                Text(value, color = tg.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 135.dp))
            }
        }, trailingIcon = { Icon(TgIcons.ChevronRight, if (expanded == key) "收起" else "展开", Modifier.size(18.dp).rotate(rotation), tint = tg.message) },
            onClick = { expanded = if (expanded == key) null else key })
    }
    @Composable fun Option(title: String, checked: Boolean, click: () -> Unit) {
        DropdownMenuItem(text = { Text(title, color = if (checked) tg.accent else tg.name) },
            modifier = Modifier.padding(start = 16.dp),
            trailingIcon = { if (checked) Icon(TgIcons.Check, null, Modifier.size(18.dp), tint = tg.accent) }, onClick = click)
    }
    @Composable fun Children(key: String, content: @Composable ColumnScope.() -> Unit) {
        AnimatedVisibility(expanded == key, enter = expandVertically(), exit = shrinkVertically()) {
            Column(Modifier.fillMaxWidth().background(tg.accent.copy(alpha = 0.05f)), content = content)
        }
    }
    Header("sort", "排序", sorts.firstOrNull { it.first == sort }?.second.orEmpty())
    Children("sort") {
        sorts.forEach { (key, title) -> Option(title, key == sort) { onSort(key) } }
        HorizontalDivider(color = tg.accent.copy(alpha = 0.12f))
        Option("升序", asc) { onDirection(true) }
        Option("降序", !asc) { onDirection(false) }
        if (sort == "taken") Option("跳转到日期…", false, onDate)
    }
    Header("types", "媒体筛选", media.filter { it.first in types }.joinToString("、") { it.second })
    Children("types") { media.forEach { (key, title) -> Option(title, key in types) { onType(key) } } }
    Header("view", "视图", modes[mode])
    Children("view") {
        modes.forEachIndexed { index, title -> Option(title, index == mode) { onMode(index) } }
        if (mode == 1) {
            HorizontalDivider(color = tg.accent.copy(alpha = 0.12f))
            (2..6).forEach { count -> Option("$count 列", columns == count) { onColumns(count) } }
        }
    }
}
