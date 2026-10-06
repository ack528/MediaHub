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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localtg.ui.tg.LocalTg
import com.localtg.ui.tg.TgIcons

/*
 * 播放器右上角的「更多」菜单(1.12.1 重做,2.0 统一对齐:1.12.0 的整块面板 + 图标磁贴太大、图标要想一会才懂,改回右上角的小浮层 + 纯文字行):
 *   - 从右上角的 ⋮ 按钮下面弹出,宽 280dp,最高不超过屏幕的 78%,超出滚动;不压暗画面,点浮层外面关闭;
 *   - 一级:倍速 / 字幕 / 音轨 / 画面比例 / 循环 / 画质增强 / 画质与解码 / 更多(每行 "名称 …… 当前值 ›",一眼能看懂);
 *   - 二级:同一个浮层里换内容,顶上是返回箭头 + 标题(选项列表打勾、分段选择、开关)。
 * 画在播放页自己的组合里(不是 Dialog 窗口),沉浸模式不会被打断。
 */

/** 所有行统一:左右内边距 16dp、行高至少 40dp、右侧固定留出 18dp 的"箭头 / 对勾"位置,这样不同行的文字和数值左右都对齐。 */
private val PadX = 16.dp
private val RowMin = 40.dp
private val SlotW = 18.dp

private val MenuBg = Color(0xF21B1B1D)
private val TextDim = Color(0x99FFFFFF)
private val Line = Color(0x1AFFFFFF)

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
            enter = fadeIn(tween(120)) + scaleIn(tween(150), initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.92f, transformOrigin = TransformOrigin(1f, 0f)),
        ) {
            Column(
                Modifier
                    .width(minOf(280.dp, maxWidth - 16.dp))
                    .heightIn(max = maxHeight * 0.78f)
                    .shadow(12.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp)).background(MenuBg)
                    .pointerInput(Unit) { detectTapGestures { } } // 吃掉浮层上的点击,不让它落到遮罩上把浮层关了
                    .pointerInput(Unit) { detectHorizontalDragGestures { c, _ -> c.consume() } },
            ) {
                if (title != null) {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = PadX, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                            Icon(TgIcons.Back, "返回", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 4.dp), content = content)
            }
        }
    }
}

/** 一行:名称 + 当前值(右对齐,淡色)+ 右侧固定宽度的箭头位(没有箭头的行也留着,数值才会和别的行右对齐)。 */
@Composable
internal fun MenuRow(title: String, value: String? = null, arrow: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = RowMin).clickable(onClick = onClick).padding(horizontal = PadX, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 14.sp)
        Box(Modifier.weight(1f))
        if (value != null) Text(value, color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false))
        Box(Modifier.padding(start = 2.dp).requiredWidth(SlotW), contentAlignment = Alignment.CenterEnd) {
            if (arrow) Icon(TgIcons.ChevronRight, null, tint = TextDim, modifier = Modifier.size(SlotW))
        }
    }
}

/** 信息行(媒体信息用):左边小标题(固定宽度,对齐),右边内容(可换行)。 */
@Composable
internal fun MenuInfo(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = PadX, vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = TextDim, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.requiredWidth(58.dp))
        Text(value, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
    }
}

/** 分组之间的细线。 */
@Composable
internal fun MenuDivider() {
    Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(0.5.dp).background(Line))
}

/** 小标题(二级页里分段)。 */
@Composable
internal fun MenuLabel(text: String) {
    Text(text, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = PadX, end = PadX, top = 10.dp, bottom = 6.dp))
}

/** 单选列表:选中的行强调色 + 右侧打勾(位置和一级菜单的箭头位一致)。 */
@Composable
internal fun MenuOptions(options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    val accent = LocalTg.current.accent
    options.forEachIndexed { i, (label, selected) ->
        Row(Modifier.fillMaxWidth().heightIn(min = RowMin).clickable { onPick(i) }.padding(horizontal = PadX, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = if (selected) accent else Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(Modifier.padding(start = 8.dp).requiredWidth(SlotW), contentAlignment = Alignment.CenterEnd) {
                if (selected) Icon(TgIcons.Check, null, tint = accent, modifier = Modifier.size(SlotW))
            }
        }
    }
}

/** 分段选择:几个并排的文字选项,选中的填充强调色。selected = -1 表示都不选中。 */
@Composable
internal fun MenuChips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val accent = LocalTg.current.accent
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PadX).clip(RoundedCornerShape(9.dp)).background(Color(0x14FFFFFF)).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(7.dp)).background(if (on) accent else Color.Transparent)
                    .clickable { onSelect(i) }.heightIn(min = 34.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Color.White else Color(0xCCFFFFFF), fontSize = 12.5.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** 开关行(开关的右边缘和其它行的箭头位对齐)。 */
@Composable
internal fun MenuSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val accent = LocalTg.current.accent
    Row(Modifier.fillMaxWidth().heightIn(min = RowMin).clickable { onChange(!checked) }.padding(start = PadX, end = PadX - 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked, onChange, modifier = Modifier.padding(start = 8.dp).scale(0.8f),
            colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = Color.White, uncheckedTrackColor = Color(0x33FFFFFF), uncheckedThumbColor = Color(0xCCFFFFFF), uncheckedBorderColor = Color.Transparent),
        )
    }
}

/** 小字说明。 */
@Composable
internal fun MenuNote(text: String, color: Color = TextDim) {
    Text(text, color = color, fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.fillMaxWidth().padding(start = PadX, end = PadX, top = 6.dp, bottom = 2.dp))
}
