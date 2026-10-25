package com.localtg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localtg.data.slideBarBounds
import com.localtg.data.slideBarThickness
import com.localtg.data.slideCountdownText
import com.localtg.ui.tg.TgIcons

/** Draw-only overlay: no touch region covers the image or video. Preview uses the same geometry. */
@Composable
internal fun SlideshowProgressBar(thickness: Float, color: Color, position: String, remaining: () -> Float) {
    val thicknessDp = slideBarThickness(thickness).dp
    Canvas(Modifier.fillMaxSize()) {
        val bounds = slideBarBounds(size.width, size.height, thicknessDp.toPx(), position, remaining())
        drawRect(color, topLeft = Offset(bounds.x, bounds.y), size = Size(bounds.width, bounds.height))
    }
}

@Composable
internal fun PhotoSlideshowCountdown(playing: Boolean, seconds: Float, remaining: () -> Float,
    onToggle: () -> Unit, modifier: Modifier = Modifier,
) {
    // Reading the animation through derived state updates only the small number, in 0.1-second steps.
    val label by remember(seconds, remaining) { derivedStateOf { slideCountdownText(seconds, remaining()) } }
    Row(modifier.clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = 0.35f)).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onToggle, modifier = Modifier.size(44.dp)) {
            Icon(if (playing) TgIcons.Pause else TgIcons.Play, if (playing) "暂停幻灯片" else "继续幻灯片", Modifier.size(22.dp), tint = Color.White)
        }
        Text(label, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
            modifier = Modifier.widthIn(min = 32.dp).semantics { contentDescription = "剩余 $label 秒" })
    }
}
