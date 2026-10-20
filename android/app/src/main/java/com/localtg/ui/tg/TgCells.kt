package com.localtg.ui.tg

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Telegram 新版(2026)设置页的样子(见 docs/17):浅灰页面底,每个小节是一张白色圆角卡片(圆角 18dp、左右留 14dp、卡片之间 14dp),
 * 卡片里第一行是蓝色粗体小节标题,行与行之间的分隔线从文字起点开始;卡片下面可以跟一段灰色说明。
 *
 * 用法:把页面内容放进 [TgCardColumn],按顺序写 小节标题([TgHeaderCell] / [TgSectionBreak])和行;
 * 布局自己把"一个小节标题到下一个小节标题之间的行"放进同一张卡片,并画卡片底色和分隔线 —— 各个设置页的写法不用改。
 */

private const val SlotStart = "tg-section-start"
private const val SlotOutside = "tg-outside"

private val CardPad = 12.dp
private val CardGap = 12.dp
// 尺寸按 Telegram 新版截图量出(同一部手机,屏宽 360dp):卡片圆角 14dp、左右留 12dp、卡片之间 12dp;卡片内文字起点 20dp;
// 正文 15sp、说明 12.5sp、小节标题 13.5sp 粗体(比原版略小一点,按要求);单行 50dp、两行约 60dp;首页图标 28dp,图标左 18dp、到文字 19dp。
// (这些数值和 Telegram 源码里的 TextSettingsCell 50dp / 16sp、HeaderCell、28dp 图标一致)
private val CardRadius = 14.dp
private val CellPad = 20.dp

private class CardGeometry {
    val cards = ArrayList<FloatArray>() // top, bottom
    val dividers = ArrayList<Float>()
}

/** 卡片式的设置页内容区:见文件开头。dividerInset = 分隔线离卡片左边的距离(和行里文字的起点一致)。 */
@Composable
fun TgCardColumn(modifier: Modifier = Modifier, dividers: Boolean = true, dividerInset: Dp = CellPad, content: @Composable () -> Unit) {
    val tg = LocalTg.current
    val geo = remember { CardGeometry() }
    Layout(
        content,
        modifier.drawBehind {
            val pad = CardPad.toPx()
            val r = CardRadius.toPx()
            for (c in geo.cards) {
                drawRoundRect(tg.bg, Offset(pad, c[0]), Size(size.width - 2 * pad, c[1] - c[0]), CornerRadius(r))
            }
            val x0 = pad + dividerInset.toPx()
            for (y in geo.dividers) drawLine(tg.divider, Offset(x0, y), Offset(size.width - pad, y), strokeWidth = 1f)
        },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val pad = CardPad.roundToPx()
        val gap = CardGap.roundToPx()
        val cardW = (w - 2 * pad).coerceAtLeast(0)
        val ids = measurables.map { it.layoutId }
        val placeables = measurables.mapIndexed { i, m ->
            val cw = if (ids[i] == SlotOutside) w else cardW
            m.measure(Constraints(minWidth = 0, maxWidth = cw, minHeight = 0, maxHeight = Constraints.Infinity))
        }
        geo.cards.clear(); geo.dividers.clear()
        val xs = IntArray(placeables.size)
        val ys = IntArray(placeables.size)
        var y = 6.dp.roundToPx() // 第一张卡片紧贴顶栏下面
        var cardTop = -1
        var prevRow = false // 上一个是卡片里的普通行(不是小节标题)
        fun closeCard() { if (cardTop >= 0) { geo.cards += floatArrayOf(cardTop.toFloat(), y.toFloat()); cardTop = -1 } }
        var any = false
        for (i in placeables.indices) {
            val p = placeables[i]
            when (ids[i]) {
                SlotStart -> {
                    if (cardTop >= 0) { closeCard(); y += gap } else if (any) y += gap
                    cardTop = y; prevRow = false
                    xs[i] = pad; ys[i] = y; y += p.height
                }
                SlotOutside -> {
                    closeCard(); prevRow = false
                    xs[i] = 0; ys[i] = y; y += p.height
                }
                else -> {
                    if (p.height == 0) { xs[i] = pad; ys[i] = y; continue }
                    if (cardTop < 0) { if (any) y += gap; cardTop = y; prevRow = false }
                    if (prevRow && dividers) geo.dividers += y.toFloat()
                    xs[i] = pad; ys[i] = y; y += p.height
                    prevRow = true
                }
            }
            any = true
        }
        closeCard()
        y += gap
        layout(w, y) { placeables.forEachIndexed { i, p -> p.place(xs[i], ys[i]) } }
    }
}

/** 卡片里的蓝色小节标题(同时开始一张新卡片)。 */
@Composable
fun TgHeaderCell(text: String) {
    val tg = LocalTg.current
    Text(
        text, color = tg.header, fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.layoutId(SlotStart).fillMaxWidth().padding(start = CellPad, end = CellPad, top = 15.dp, bottom = 4.dp),
    )
}

/** 不带标题地开始一张新卡片(设置首页按组分卡片用)。 */
@Composable
fun TgSectionBreak() {
    Spacer(Modifier.layoutId(SlotStart).height(0.dp))
}

/** 一行单元格的外壳:可点、最小高度 54dp;底色和分隔线由 [TgCardColumn] 画。 */
@Composable
fun TgCell(onClick: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 50.dp).padding(horizontal = CellPad, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

/** 标题 + 右侧当前值(蓝色)+ 下面的灰色说明。 */
@Composable
fun TgTextCell(title: String, value: String? = null, subtitle: String? = null, titleColor: Color = Color.Unspecified, onClick: (() -> Unit)? = null) {
    val tg = LocalTg.current
    TgCell(onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = if (titleColor == Color.Unspecified) tg.name else titleColor, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (value != null) {
                Text(
                    value, color = tg.accent, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp).widthIn(max = 190.dp),
                )
            }
        }
        if (subtitle != null) Text(subtitle, color = tg.message, fontSize = 12.5.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

/** 标题 + 说明,右边开关;整行可点。 */
@Composable
fun TgCheckCell(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val tg = LocalTg.current
    TgCell({ onChange(!checked) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, color = tg.name, fontSize = 15.sp)
                if (subtitle != null) Text(subtitle, color = tg.message, fontSize = 12.5.sp, modifier = Modifier.padding(top = 3.dp))
            }
            TgSwitch(checked)
        }
    }
}

/** 只读的"标题 + 值":上面灰色小字,下面正文。 */
@Composable
fun TgDetailCell(title: String, value: String) {
    val tg = LocalTg.current
    TgCell(null) {
        Text(title, color = tg.message, fontSize = 12.5.sp)
        Text(value, color = tg.name, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/** 设置首页的行:左边彩色圆角方块图标,右边标题 + 灰色说明(Telegram 新版设置首页)。 */
@Composable
fun TgIconCell(icon: ImageVector, iconColor: Color, title: String, subtitle: String?, onClick: () -> Unit) {
    val tg = LocalTg.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 50.dp).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(iconColor), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f).padding(start = 19.dp)) {
            Text(title, color = tg.name, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = tg.message, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** 卡片外的灰色说明(放在卡片下面)。 */
@Composable
fun TgInfoCell(text: String) {
    val tg = LocalTg.current
    Text(
        text, color = tg.message, fontSize = 13.sp,
        modifier = Modifier.layoutId(SlotOutside).fillMaxWidth().padding(start = CardPad + CellPad, end = CardPad + CellPad, top = 7.dp),
    )
}

/** 旧版的灰色分组间隔,已被卡片取代;保留给还没改成卡片的地方。 */
@Composable
fun TgSectionGap(height: Dp = 12.dp) {
    Box(Modifier.fillMaxWidth().height(height).background(LocalTg.current.gray))
}

/**
 * Telegram 新版的开关:轨道约 40×18dp 的胶囊,白色圆形滑块(直径 22dp)比轨道略高,带一圈和轨道同色的描边;
 * 关:灰轨道,滑块在左;开:蓝轨道,滑块在右。
 */
@Composable
fun TgSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val tg = LocalTg.current
    // 液态的手感:滑块用弹簧移动(到位时轻微过冲),移动途中被拉长成胶囊、到位后缩回圆形
    val p by animateFloatAsState(
        if (checked) 1f else 0f,
        if (com.localtg.ui.fineMotionOn()) androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 500f) else tween(0),
        label = "switch",
    )
    Canvas(modifier.size(width = 40.dp, height = 22.dp)) {
        val trackW = 35.dp.toPx()
        val trackH = 16.dp.toPx()
        val left = (size.width - trackW) / 2f
        val top = (size.height - trackH) / 2f
        val pc = p.coerceIn(0f, 1f)
        val track = lerp(tg.switchOff, tg.switchOn, pc)
        drawRoundRect(track, Offset(left, top), Size(trackW, trackH), CornerRadius(trackH / 2f))
        val r = 9.5f.dp.toPx() - 1.dp.toPx()
        val cx = left + r - 1.dp.toPx() + (trackW - 2 * r + 2.dp.toPx()) * p
        val cy = size.height / 2f
        val stretch = r * 0.55f * kotlin.math.sin(Math.PI.toFloat() * pc) // 途中拉长
        val tl = Offset(cx - r - stretch / 2f, cy - r)
        val sz = Size(2 * r + stretch, 2 * r)
        drawRoundRect(Color.White, tl, sz, CornerRadius(r))
        drawRoundRect(track, tl, sz, CornerRadius(r), style = Stroke(2.dp.toPx()))
    }
}
