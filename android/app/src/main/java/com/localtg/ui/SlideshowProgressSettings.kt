package com.localtg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localtg.data.*
import com.localtg.ui.tg.*
import java.util.Locale
import kotlin.math.round

@Composable
internal fun SlideshowProgressSettings(settings: AppSettings, update: (AppSettings.() -> AppSettings) -> Unit) {
    val tg = LocalTg.current
    var thickness by remember(settings.slideshowBarThickness) { mutableFloatStateOf(slideBarThickness(settings.slideshowBarThickness)) }
    val hex = parseSlideBarColor(settings.slideshowBarColor) ?: DEFAULT_SLIDE_BAR_COLOR
    var colorOpen by remember { mutableStateOf(false) }
    val position = slideBarPosition(settings.slideshowBarPosition)
    ChoiceRow("图片全屏提示", slidePhotoIndicator(settings.slideshowPhotoIndicator), listOf("bar" to "进度条", "countdown" to "暂停按钮和倒计时", "none" to "不显示"),
        desc = "图片隐藏信息时使用，倒计时显示在左下角暂停按钮右侧") { value -> update { copy(slideshowPhotoIndicator = value) } }
    ChoiceRow("进度条位置", position, listOf("top" to "上", "bottom" to "下", "left" to "左", "right" to "右"),
        desc = "图片和视频进度条共用") { value -> update { copy(slideshowBarPosition = value) } }
    TgCell(null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("进度条粗细", color = tg.name, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(String.format(Locale.ROOT, "%.1f dp", thickness), color = tg.accent, fontSize = 13.sp)
        }
        Slider(value = thickness, onValueChange = { thickness = round(it * 2f) / 2f },
            onValueChangeFinished = { update { copy(slideshowBarThickness = slideBarThickness(thickness)) } },
            valueRange = 0.5f..12f, steps = 22, modifier = Modifier.fillMaxWidth().height(40.dp),
            colors = SliderDefaults.colors(thumbColor = tg.accent, activeTrackColor = tg.accent))
        ProgressSample(thickness, Color(slideBarArgb(hex)), position)
        Text("0.5–12 dp · 图片和视频幻灯片共用，保存后立即生效", color = tg.message, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
    TgTextCell("进度条颜色", value = hex, subtitle = "常用颜色或自定义 #RRGGBB 色值", onClick = { colorOpen = true })
    TgTextCell("恢复全屏提示默认设置", subtitle = "顶部进度条 · 浅灰色 · 4 dp", onClick = {
        update { copy(slideshowBarThickness = 4f, slideshowBarColor = DEFAULT_SLIDE_BAR_COLOR, slideshowBarPosition = "top", slideshowPhotoIndicator = "bar") }
    })
    if (colorOpen) {
        var text by remember { mutableStateOf(hex) }
        val parsed = parseSlideBarColor(text)
        AlertDialog(onDismissRequest = { colorOpen = false }, title = { Text("进度条颜色") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val presets = listOf("#CCCCCC", "#FFFFFF", "#000000", "#4DA6FF", "#48C153", "#FF6B6B", "#FFB74D", "#B39DDB")
                presets.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { color ->
                            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(slideBarArgb(color)))
                                .border(if (parsed == color) 3.dp else 1.dp, if (parsed == color) tg.accent else tg.message.copy(alpha = 0.4f), CircleShape)
                                .semantics { contentDescription = "颜色 $color" }
                                .clickable { text = color }, contentAlignment = Alignment.Center) {
                                if (parsed == color) Icon(TgIcons.Check, "已选择 $color", tint = if (color == "#000000") Color.White else Color.Black, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Text("自定义颜色（6 位十六进制）", color = tg.message, fontSize = 12.sp)
                BasicTextField(value = text, onValueChange = { text = it.take(32) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(tg.gray).padding(horizontal = 12.dp, vertical = 10.dp),
                    textStyle = LocalTextStyle.current.copy(color = tg.name, fontSize = 15.sp), cursorBrush = SolidColor(tg.accent))
                if (parsed == null) Text("请输入如 #4DA6FF 的有效色值", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                ProgressSample(thickness, Color(slideBarArgb(parsed ?: hex)), position)
            }
        }, confirmButton = { TextButton(enabled = parsed != null, onClick = { update { copy(slideshowBarColor = parsed!!) }; colorOpen = false }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { colorOpen = false }) { Text("取消") } })
    }
}

@Composable
private fun ProgressSample(thickness: Float, color: Color, position: String) {
    Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(6.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF222222), Color(0xFFE8E8E8))))) {
        SlideshowProgressBar(thickness, color, position, { 0.72f })
    }
}
