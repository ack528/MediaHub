package com.localtg.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localtg.data.BrowseHistoryResult
import com.localtg.ui.tg.LocalTg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun BrowseHistoryDialog(vm: ChatViewModel, close: () -> Unit) {
    val tg = LocalTg.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<BrowseHistoryResult?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val format = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = ""
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "操作失败" }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { action { result = vm.history() } }
    AlertDialog(onDismissRequest = close, title = { Text("浏览历史") }, text = {
        Column {
            val policy = result?.config
            Text(if (policy?.enabled == false) "服务器已关闭新历史保存" else "自动保存位置 · 点击记录恢复视图与浏览位置", color = tg.message, fontSize = 12.sp)
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = !busy && policy?.enabled != false, onClick = { action { vm.saveCheckpoint(); result = vm.history() } }) { Text("保存当前位置") }
                TextButton(enabled = !busy, onClick = { action { result = vm.history() } }) { Text("刷新") }
            }
            val entries = result?.entries.orEmpty()
            if (!busy && result != null && entries.isEmpty()) Text("暂无历史记录", color = tg.message, modifier = Modifier.padding(vertical = 20.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(entries, key = { it.savedAt }) { entry ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable(enabled = !busy) { action { vm.restoreCheckpoint(entry.view); close() } }.padding(vertical = 10.dp)) {
                            Text(entry.view.itemName.ifEmpty { "浏览位置 #${entry.view.itemId}" }, color = tg.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(format.format(Date(entry.savedAt)) + " · " + (if (!entry.view.grid) "聊天流" else if (entry.view.feed) "瀑布流" else "网格"), color = tg.message, fontSize = 12.sp)
                            if (entry.view.device.isNotEmpty()) Text(entry.view.device, color = tg.message, fontSize = 11.sp)
                        }
                        TextButton(enabled = !busy, onClick = { action { vm.deleteCheckpoint(entry.savedAt); result = vm.history() } }) { Text("删除", fontSize = 12.sp) }
                    }
                    HorizontalDivider(color = tg.message.copy(alpha = 0.12f))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("关闭") } }, dismissButton = {
        TextButton(enabled = !busy && result?.entries?.isNotEmpty() == true, onClick = { confirmClear = true }) { Text("清空历史") }
    })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空这个群的浏览历史？") }, text = { Text("服务器上的历史也会删除。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; action { vm.deleteCheckpoint(null); result = vm.history() } }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}
