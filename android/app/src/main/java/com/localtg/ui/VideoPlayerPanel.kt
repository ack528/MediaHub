package com.localtg.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localtg.ui.tg.LocalTg
import com.localtg.ui.tg.TgIcons

/*
 * 播放器右上角的「更多」菜单(1.12.1 重做,2.0 统一对齐:1.12.0 的整块面板 + 图标磁贴太大、图标要想一会才懂,改回右上角的小浮层 + 纯文字行):
 *   - 从右上角的 ⋮ 按钮下面弹出,宽 232dp,最高不超过屏幕的 62%,超出滚动;不压暗画面,点浮层外面关闭;
 *   - 一级:倍速 / 字幕 / 音轨 / 画面比例 / 循环 / 画质增强 / 画质与解码 / 更多(每行 "名称 …… 当前值 ›",一眼能看懂);
 *   - 二级:同一个浮层里换内容,顶上是返回箭头 + 标题(选项列表打勾、分段选择、开关)。
 * 画在播放页自己的组合里(不是 Dialog 窗口),沉浸模式不会被打断。
 */

/*
 * 2026-11 重绘:和软件主体(网页界面)一致 —— 浮层是深色主题的 overlay 卡片(24dp 圆角),行是 16dp 圆角、按下有浅色底,
 * 字号 14 / 12sp,弱化文字 #A1A1AA,分隔线 #3C3C41,分段选择是网页标签页的样式(深灰轨道 + 浅一档的选中块),强调色 #0485F7。
 * 位置不变:仍然从右上角 ⋮ 下面弹出。
 */

/** 卡片内边距 6dp(网页 p-1.5);行内左右 10dp;行高至少 40dp;右侧固定留 16dp 的"箭头 / 对勾"位,各行文字和数值左右对齐。 */
private val CardPad = 6.dp
private val PadX = 10.dp
private val RowMin = 40.dp
private val SlotW = 16.dp

/** 浮层容器。title = null 表示一级页(没有返回箭头)。 */
@Composable
internal fun PlayerMenuHost(
    open: Boolean, title: String?, onBack: () -> Unit, onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(enabled = open) { if (title != null) onBack() else onClose() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (open) {
            // 全屏透明遮罩:点浮层外面关闭,横向拖动不漏给下面的翻页
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) { detectHorizontalDragGestures { c, _ -> c.consume() } }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            )
        }
        AnimatedVisibility(
            open,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 50.dp, end = 8.dp),
            enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.92f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.94f, transformOrigin = TransformOrigin(1f, 0f)),
        ) {
            Column(
                Modifier
                    .width(minOf(256.dp, maxWidth - 16.dp))
                    .heightIn(max = minOf(420.dp, maxHeight * 0.66f))
                    .shadow(16.dp, Vx.CardShape)
                    .clip(Vx.CardShape).background(Vx.Overlay)
                    .pointerInput(Unit) { detectTapGestures { } } // 吃掉浮层上的点击,不让它落到遮罩上把浮层关了
                    .pointerInput(Unit) { detectHorizontalDragGestures { c, _ -> c.consume() } }
                    .padding(CardPad),
            ) {
                if (title != null) {
                    Row(Modifier.fillMaxWidth().padding(end = PadX, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                            Icon(HeroIcons.Back, "返回", tint = Vx.Fg, modifier = Modifier.size(18.dp))
                        }
                        Text(title, color = Vx.Fg, style = Vx.BodyMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 2.dp))
                    }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
            }
        }
    }
}

/** 一行:名称 + 当前值(右对齐,弱化色)+ 右侧固定宽度的箭头位(没有箭头的行也留着,数值才会和别的行右对齐)。 */
@Composable
internal fun MenuRow(title: String, value: String? = null, arrow: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = RowMin).clip(Vx.RowShape).clickable(onClick = onClick).padding(horizontal = PadX, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Vx.Fg, style = Vx.BodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
        if (value != null) Text(value, color = Vx.Muted, style = Vx.Body, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.padding(start = 12.dp).widthIn(max = 110.dp))
        Box(Modifier.padding(start = 4.dp).requiredWidth(SlotW), contentAlignment = Alignment.CenterEnd) {
            if (arrow) Icon(HeroIcons.ChevronRight, null, tint = Vx.Muted, modifier = Modifier.size(14.dp))
        }
    }
}

/** 信息行:左边小标题(固定宽度,对齐),右边内容(可换行)。 */
@Composable
internal fun MenuInfo(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = PadX, vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = Vx.Muted, style = Vx.Small.copy(lineHeight = 20.sp), modifier = Modifier.requiredWidth(56.dp))
        Text(value, color = Vx.Fg, style = Vx.Body, modifier = Modifier.weight(1f))
    }
}

/** 分组之间的细线(网页 ListDivider:左右缩进)。 */
@Composable
internal fun MenuDivider() {
    Box(Modifier.padding(horizontal = PadX, vertical = 4.dp).fillMaxWidth().height(1.dp).background(Vx.Separator))
}

/** 小标题(二级页里分段)。 */
@Composable
internal fun MenuLabel(text: String) {
    Text(text, color = Vx.Muted, style = Vx.Small.copy(fontWeight = FontWeight.Medium), modifier = Modifier.padding(start = PadX, end = PadX, top = 10.dp, bottom = 6.dp))
}

/** 单选列表:选中的行强调色 + 右侧打勾(位置和一级菜单的箭头位一致)。 */
@Composable
internal fun MenuOptions(options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    options.forEachIndexed { i, (label, selected) ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = RowMin).clip(Vx.RowShape).clickable { onPick(i) }.padding(horizontal = PadX, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = if (selected) Vx.Accent else Vx.Fg, style = if (selected) Vx.BodyMedium else Vx.Body, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(Modifier.padding(start = 8.dp).requiredWidth(SlotW), contentAlignment = Alignment.CenterEnd) {
                if (selected) Icon(HeroIcons.Check, null, tint = Vx.Accent, modifier = Modifier.size(SlotW))
            }
        }
    }
}

/** 分段选择(网页 Tabs / ToggleButtonGroup):深灰全圆轨道,选中的是浅一档的圆块。selected = -1 表示都不选中。 */
@Composable
internal fun MenuChips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).clip(CircleShape).background(Vx.Surface2).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(CircleShape).background(if (on) Vx.Segment else Color.Transparent)
                    .clickable { onSelect(i) }.heightIn(min = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Vx.Fg else Vx.Muted, style = Vx.Small.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            }
        }
    }
}

/** 开关行(网页 Switch:选中是强调色轨道 + 白色圆点;开关的右边缘和其它行的箭头位对齐)。 */
@Composable
internal fun MenuSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = RowMin).clip(Vx.RowShape).clickable { onChange(!checked) }.padding(start = PadX, end = PadX - 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Vx.Fg, style = Vx.BodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked, onChange, modifier = Modifier.padding(start = 8.dp).scale(0.8f),
            colors = SwitchDefaults.colors(
                checkedTrackColor = Vx.Accent, checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = Vx.Segment, uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** 小字说明。 */
@Composable
internal fun MenuNote(text: String, color: Color = Vx.Muted) {
    Text(text, color = color, style = Vx.Small.copy(lineHeight = 17.sp), modifier = Modifier.fillMaxWidth().padding(start = PadX, end = PadX, top = 6.dp, bottom = 2.dp))
}
