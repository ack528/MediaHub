package com.localtg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localtg.AppContainer
import com.localtg.ChatActivity
import com.localtg.RandomActivity
import com.localtg.SettingsActivity
import com.localtg.ui.glass.GlassHost
import com.localtg.ui.glass.GlassKind
import com.localtg.ui.glass.LocalBottomInset
import com.localtg.ui.glass.glass
import com.localtg.ui.tg.LocalTg
import com.localtg.ui.tg.TgIcons

/** 底栏的四个标签(对应 Telegram 新版的 聊天 / 联系人 / 设置 / 个人资料)。 */
private val TABS: List<Pair<ImageVector, String>> by lazy {
    listOf(TgIcons.Chat to "文件夹", TgIcons.Shuffle to "随机", TgIcons.Search to "搜索", TgIcons.Settings to "设置")
}

private val BarH = 56.dp
private val BarSide = 15.dp
private val BarGap = 2.dp // 原版底栏离底部手势条约 16dp

/**
 * 主界面:四个标签共用一个页面,底部是悬浮的液态玻璃底栏(Telegram 新版的样子),玻璃采样正在显示的那个标签的内容。
 * 标签里点进去的页面(文件夹 → 聊天、设置 → 二级页)仍是各自的 Activity,系统转场和预测性返回不变。
 * 图片 / 视频查看器盖在整个主界面上面(包括底栏)。
 */
@Composable
fun MainTabs(c: AppContainer) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var drawerOpen by remember { mutableStateOf(false) }
    val holder = rememberSaveableStateHolder()
    val hap = LocalHaptics.current
    androidx.activity.compose.BackHandler(enabled = tab != 0) { tab = 0 } // 别的标签按返回 = 回到文件夹
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset = nav + BarGap + BarH + 8.dp
    ViewerNavHost(c) { openViewer ->
        GlassHost(
            content = {
                CompositionLocalProvider(LocalBottomInset provides bottomInset) {
                    holder.SaveableStateProvider(tab) {
                        when (tab) {
                            0 -> DialogsScreen(
                                c,
                                onOpen = { d, _ -> ChatActivity.start(ctx, d.id, d.title) },
                                onSettings = { tab = 3 },
                                onSearch = { tab = 2 },
                                onRandom = { label, roots, _ -> RandomActivity.start(ctx, label, roots) },
                                onSection = { SettingsActivity.start(ctx, it) },
                                onDrawer = { drawerOpen = it },
                            )
                            1 -> RandomScreen(c, null, null, onBack = null, onOpenViewer = openViewer)
                            2 -> SearchScreen(c, onBack = null, onOpenViewer = openViewer)
                            else -> SettingsScreen(c, null, onBack = null, onOpen = { SettingsActivity.start(ctx, it) })
                        }
                    }
                }
            },
            overlay = {
                if (!(tab == 0 && drawerOpen)) {
                    com.localtg.ui.glass.LiquidTabBar(
                        TABS, tab, Modifier.align(Alignment.BottomCenter).padding(start = BarSide, end = BarSide, bottom = nav + BarGap),
                    ) { if (it != tab) { hap.perform(Hap.Tick); tab = it } }
                }
            },
        )
    }
}

