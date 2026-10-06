package com.localtg.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localtg.ui.tg.LocalTg
import com.localtg.ui.tg.TgIcons

/*
 * 播放器的"播放设置"面板:以前右上角是一个有十几项的下拉菜单,现在分成
 *   顶栏:返回 / 标题 / (有字幕才显示)字幕 / 画质增强 / 播放设置;
 *   面板:快捷磁贴(倍速、字幕、音轨、画面比例、睡眠定时、单个循环、A-B 循环、跳转、小窗)+ 分组的行(播放画质、画质增强、解码方式、保存、媒体信息),
 *        行点进去是二级页(选项列表 / 分段选择 / 开关),带返回。
 * 面板画在播放页自己的组合里(不是 Dialog 窗口),所以沉浸模式不会被打断,视频画面一直在后面。
 * 横屏时是右侧 380dp 宽的侧边面板,竖屏时是底部面板(最高 72% 屏幕高度)。
 */

private val PanelBg = Color(0xF2151517)
private val TileBg = Color(0x14FFFFFF)
private val TextDim = Color(0x99FFFFFF)

/** 面板容器:背景遮罩 + 侧边 / 底部面板 + 标题栏(二级页有返回箭头)+ 可滚动内容。 */
@Composable
internal fun PlayerPanelHost(
    open: Boolean, title: String, canBack: Boolean,
    onBack: () -> Unit, onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(enabled = open) { if (canBack) onBack() else onClose() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        AnimatedVisibility(open, enter = fadeIn(tween(160)), exit = fadeOut(tween(140))) {
            Box(
                Modifier.fillMaxSize().background(Color(0x66000000))
                    .pointerInput(Unit) { detectHorizontalDragGestures { c, _ -> c.consume() } } // 横向拖动不能漏给下面的翻页(切到上一个 / 下一个视频)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            )
        }
        val shape = if (landscape) RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp) else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        AnimatedVisibility(
            open,
            modifier = Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter),
            enter = if (landscape) slideInHorizontally(tween(220)) { it } + fadeIn(tween(160)) else slideInVertically(tween(220)) { it } + fadeIn(tween(160)),
            exit = if (landscape) slideOutHorizontally(tween(180)) { it } + fadeOut(tween(140)) else slideOutVertically(tween(180)) { it } + fadeOut(tween(140)),
        ) {
            Column(
                Modifier
                    .then(if (landscape) Modifier.width(minOf(380.dp, maxWidth * 0.55f)).fillMaxHeight() else Modifier.fillMaxWidth().heightIn(max = maxHeight * 0.72f))
                    .clip(shape).background(PanelBg)
                    .pointerInput(Unit) { detectTapGestures { } } // 吃掉面板上的点击,不让它落到遮罩上把面板关了
                    .pointerInput(Unit) { detectHorizontalDragGestures { c, _ -> c.consume() } }
                    .then(if (landscape) Modifier.statusBarsPadding() else Modifier)
                    .navigationBarsPadding(),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = if (canBack) 6.dp else 20.dp, end = 6.dp, top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (canBack) PanelIcon(TgIcons.Back, "返回", onBack)
                    Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(start = if (canBack) 4.dp else 0.dp))
                    PanelIcon(TgIcons.Close, "关闭", onClose)
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp), content = content)
            }
        }
    }
}

@Composable
private fun PanelIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/** 分组标题。 */
@Composable
internal fun PanelSection(title: String) {
    Text(title, color = TextDim, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 8.dp))
}

/** 快捷磁贴。 */
internal class PanelTile(val icon: ImageVector, val label: String, val value: String? = null, val active: Boolean = false, val onClick: () -> Unit)

/** 3 列的磁贴网格(最后一行不满用空位补齐,保证每个磁贴一样宽)。 */
@Composable
internal fun TileGrid(tiles: List<PanelTile>, columns: Int = 3) {
    val accent = LocalTg.current.accent
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(if (t.active) accent.copy(alpha = 0.22f) else TileBg)
                            .then(if (t.active) Modifier.border(BorderStroke(1.dp, accent.copy(alpha = 0.55f)), RoundedCornerShape(14.dp)) else Modifier)
                            .clickable(onClick = t.onClick).padding(vertical = 12.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(t.icon, null, tint = if (t.active) accent else Color.White, modifier = Modifier.size(24.dp))
                        Text(t.label, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        Text(t.value ?: " ", color = if (t.active) accent else TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 分组里的一行:图标 + 标题 + 当前值 + 右箭头。多行放进 [PanelGroup] 里连成一张圆角卡片。 */
@Composable
internal fun PanelRow(icon: ImageVector, title: String, value: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Text(title, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(start = 14.dp).weight(1f))
        if (value != null) Text(value, color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false))
        Icon(TgIcons.ChevronRight, null, tint = TextDim, modifier = Modifier.padding(start = 4.dp).size(20.dp))
    }
}

/** 把几行 [PanelRow] / [PanelSwitchRow] 包成一张圆角卡片。 */
@Composable
internal fun PanelGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(TileBg), content = content)
}

/** 单选列表(选中的行右边打勾,强调色)。 */
@Composable
internal fun PanelOptions(options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    val accent = LocalTg.current.accent
    PanelGroup {
        options.forEachIndexed { i, (label, selected) ->
            Row(Modifier.fillMaxWidth().clickable { onPick(i) }.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = if (selected) accent else Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (selected) Icon(TgIcons.Check, null, tint = accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** 分段选择(几个并排的选项,选中的填充强调色)。 */
@Composable
internal fun PanelSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val accent = LocalTg.current.accent
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TileBg).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) accent else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 10.dp, horizontal = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Color.White else Color(0xCCFFFFFF), fontSize = 13.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 带说明的开关行。 */
@Composable
internal fun PanelSwitchRow(icon: ImageVector, title: String, desc: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    val accent = LocalTg.current.accent
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            if (desc != null) Text(desc, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = Color.White, uncheckedTrackColor = Color(0x33FFFFFF), uncheckedThumbColor = Color(0xCCFFFFFF), uncheckedBorderColor = Color.Transparent),
        )
    }
}

/** 小字说明。 */
@Composable
internal fun PanelNote(text: String, color: Color = TextDim) {
    Text(text, color = color, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp))
}
