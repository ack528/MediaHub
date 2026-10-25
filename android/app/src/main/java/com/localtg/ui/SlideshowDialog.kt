package com.localtg.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localtg.data.AppSettings
import com.localtg.data.SlideshowOptions

@Composable
fun SlideshowDialog(settings: AppSettings, randomSource: Boolean, onDismiss: () -> Unit, onStart: (SlideshowOptions) -> Unit) {
    var seconds by remember { mutableIntStateOf(settings.slideshowSeconds.coerceIn(3, 60)) }
    var order by remember { mutableStateOf(if (randomSource && settings.slideshowOrder == "reverse") "forward" else settings.slideshowOrder) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("幻灯片播放") },
        text = {
            Column {
                Text("混播当前${if (randomSource) "随机范围" else "群组"}的图片和视频；视频从头播放，播完后切换。")
                Text("图片停留时间", Modifier.padding(top = 16.dp))
                listOf(3, 5, 10, 15, 30).chunked(3).forEach { values ->
                    Row { values.forEach { value ->
                        TextButton(onClick = { seconds = value }, modifier = Modifier.weight(1f)) { Text(if (seconds == value) "✓${value}秒" else "${value}秒") }
                    } }
                }
                Text("播放顺序")
                val orders = buildList { add("forward" to if (randomSource) "按当前随机序列" else "按列表顺序"); if (!randomSource) add("reverse" to "列表倒序"); add("shuffle" to "随机播放") }
                orders.forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().clickable { order = value }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = order == value, onClick = { order = value })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(SlideshowOptions(seconds, order)) }) { Text("开始播放") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Shared entry for group and random slideshows. */
suspend fun openSlideshow(
    ctx: android.content.Context, c: com.localtg.AppContainer, options: SlideshowOptions, onOpen: (Int) -> Unit,
    load: suspend () -> com.localtg.data.ViewerFeed,
) {
    try {
        val feed = load()
        if (feed.items.isEmpty()) {
            android.widget.Toast.makeText(ctx, "没有可播放的图片或视频", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        c.settings.update { copy(slideshowSeconds = options.imageSeconds, slideshowOrder = options.order) }
        c.viewerReturn.value = null
        c.viewerFeed = feed
        onOpen(0)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) {
        com.localtg.AppLog.w("slideshow", "启动幻灯片失败", e)
        android.widget.Toast.makeText(ctx, com.localtg.data.friendlyError(e), android.widget.Toast.LENGTH_LONG).show()
    }
}
