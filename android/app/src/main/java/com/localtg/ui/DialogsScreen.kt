package com.localtg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import com.localtg.AppContainer
import com.localtg.data.Dialog
import com.localtg.data.friendlyError
import com.localtg.ui.tg.BarIcon
import com.localtg.ui.tg.LocalTg
import com.localtg.ui.tg.TgAvatar
import com.localtg.ui.tg.TgBar
import com.localtg.ui.tg.TgIcons
import com.localtg.ui.tg.toItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class DialogsViewModel(private val c: AppContainer) : ViewModel() {
    val dialogs = MutableStateFlow<List<Dialog>>(emptyList())
    val loading = MutableStateFlow(true)
    val error = MutableStateFlow<String?>(null)

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true; error.value = null
            runCatching {
                var cursor: String? = null
                val all = mutableListOf<Dialog>()
                do {
                    val r = c.api.dialogs(cursor = cursor, limit = 100)
                    all += r.dialogs
                    dialogs.value = all.toList() // 每取到一页就更新,列表渐进出现
                    cursor = r.nextCursor
                } while (cursor != null)
            }.onFailure { error.value = friendlyError(it) }
            c.dialogCache = dialogs.value.associateBy { it.id }
            loading.value = false
        }
    }
}

/** 聊天列表:一个母文件夹 = 一个群。布局取自 Telegram DialogCell:行高 70dp、头像 52dp、文字起点 72dp。左上角三条杠展开侧边栏。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DialogsScreen(
    c: AppContainer, onOpen: (Dialog) -> Unit, onSettings: () -> Unit, onSearch: () -> Unit, onSection: (String) -> Unit,
) {
    val vm: DialogsViewModel = viewModel(factory = viewModelFactory { initializer { DialogsViewModel(c) } })
    val all by vm.dialogs.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val scope = rememberCoroutineScope()
    val tg = LocalTg.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var confirmLogout by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }

    // 盘符 = Telegram 的聊天分组标签
    val labels = remember(all) { all.map { it.rootLabel }.distinct().sorted() }
    var tab by remember { mutableIntStateOf(0) } // 0 = 全部
    val shown = if (tab == 0 || tab > labels.size) all else all.filter { it.rootLabel == labels[tab - 1] }

    fun go(f: () -> Unit) { scope.launch { drawer.close(); f() } }
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            AppDrawer(
                c, onSearch = { go(onSearch) }, onRefresh = { go { vm.refresh() } }, onSettings = { go(onSettings) },
                onLog = { go { onSection("log") } }, onAbout = { go { onSection("about") } },
                onLogout = { go { confirmLogout = true } },
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            TgBar {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarIcon(TgIcons.Menu, "菜单") { scope.launch { drawer.open() } }
                    Text("本地浏览", Modifier.weight(1f).padding(start = 8.dp), color = tg.barText, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    BarIcon(TgIcons.Search, "搜索", onSearch)
                }
                if (labels.size > 1) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        FolderTab("全部", tab == 0) { tab = 0 }
                        labels.forEachIndexed { i, l -> FolderTab(l, tab == i + 1) { tab = i + 1 } }
                    }
                }
            }

            // 下拉刷新
            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = loading && all.isNotEmpty(), onRefresh = { vm.refresh() }, modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                when {
                    shown.isEmpty() && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    shown.isEmpty() && error != null -> Column(
                        Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { vm.refresh() }) { Text("重试") }
                    }
                    shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("没有可显示的文件夹", color = tg.message)
                    }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.id }) { d -> Box(Modifier.animateItem()) { DialogRow(d, c, onClick = { onOpen(d) }) } }
                    }
                }
            }
        }
    }

    if (confirmLogout) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录") }, text = { Text("退出后需要重新输入账号密码。服务器地址会保留。") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; scope.launch { c.api.logout(); c.session.clearToken() } }) { Text("退出") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
        )
    }
}

/** 侧边栏(Telegram 风格):顶部是 logo、应用名和服务器地址,下面是常用入口。 */
@Composable
private fun AppDrawer(
    c: AppContainer, onSearch: () -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit,
    onLog: () -> Unit, onAbout: () -> Unit, onLogout: () -> Unit,
) {
    val tg = LocalTg.current
    val session by c.session.session.collectAsState()
    val host = remember(session) { session?.baseUrl?.substringAfter("://").orEmpty() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ver = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }
    ModalDrawerSheet(
        drawerContainerColor = tg.bg, modifier = Modifier.width(304.dp),
        windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0), // 状态栏高度由蓝色头部自己处理
    ) {
        Column(Modifier.fillMaxWidth().background(tg.bar).statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp)) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(com.localtg.R.drawable.ic_logo), null,
                Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
            )
            Text("本地浏览", Modifier.padding(top = 12.dp), color = tg.barText, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(host.ifEmpty { "未连接" }, Modifier.padding(top = 2.dp), color = tg.barSub, fontSize = 13.sp, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        DrawerItem(TgIcons.Search, "搜索", onSearch)
        DrawerItem(TgIcons.Refresh, "刷新列表", onRefresh)
        DrawerItem(TgIcons.Settings, "设置", onSettings)
        DrawerItem(TgIcons.File, "日志与诊断", onLog)
        DrawerItem(TgIcons.Info, "关于", onAbout)
        Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(1.dp).background(tg.divider))
        DrawerItem(TgIcons.Logout, "退出登录", onLogout, tint = Color(0xFFE53935))
        Spacer(Modifier.weight(1f))
        Text("版本 $ver", Modifier.padding(18.dp).navigationBarsPadding(), color = tg.message, fontSize = 12.sp)
    }
}

@Composable
private fun DrawerItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, tint: Color? = null) {
    val tg = LocalTg.current
    Row(
        Modifier.fillMaxWidth().height(50.dp).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(icon, null, tint = tint ?: tg.message, modifier = Modifier.size(24.dp))
        Text(label, Modifier.padding(start = 28.dp), color = tint ?: tg.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FolderTab(text: String, selected: Boolean, onClick: () -> Unit) {
    val tg = LocalTg.current
    Column(Modifier.height(44.dp).clickable(onClick = onClick).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(text, color = if (selected) tg.barText else tg.barTabIdle, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Box(
            Modifier.height(3.dp).fillMaxWidth().clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                .background(if (selected) tg.barText else Color.Transparent),
        )
    }
}

@Composable
private fun DialogRow(d: Dialog, c: AppContainer, onClick: () -> Unit) {
    val tg = LocalTg.current
    Box(Modifier.fillMaxWidth().height(70.dp).clickable(onClick = onClick)) {
        Box(Modifier.padding(start = 11.dp, top = 9.dp)) {
            TgAvatar(d.title, d.id, 52.dp, d.last?.toItem(d.id), c.api)
        }
        Column(Modifier.fillMaxSize().padding(start = 72.dp, end = 12.dp, top = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    d.title, Modifier.weight(1f), color = tg.name, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(d.last?.takenAt?.let { formatDialogTime(it) }.orEmpty(), color = tg.date, fontSize = 13.sp)
            }
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                val last = d.last
                if (last != null) {
                    val kind = when (last.type) { "video" -> "视频"; "gif" -> "GIF"; "audio" -> "音频"; else -> "照片" }
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = tg.attach)) { append(kind) }
                            append("  ${last.name}")
                        },
                        Modifier.weight(1f), color = tg.message, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Box(Modifier.weight(1f))
                }
                // 数量:相当于"已静音群"的灰色计数
                Box(
                    Modifier.padding(start = 6.dp).defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).clip(RoundedCornerShape(10.dp))
                        .background(tg.badgeMuted).padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("${d.mediaCount}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
        }
        Box(Modifier.align(Alignment.BottomStart).padding(start = 72.dp).fillMaxWidth().height(1.dp).background(tg.divider))
    }
}

/** 当天显示时间,今年内显示月日,更早显示年月日(Telegram 的做法)。 */
fun formatDialogTime(rfc: String): String = runCatching {
    val t = java.time.OffsetDateTime.parse(rfc).atZoneSameInstant(java.time.ZoneId.systemDefault())
    val now = java.time.ZonedDateTime.now()
    when {
        t.toLocalDate() == now.toLocalDate() -> t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        t.year == now.year -> t.format(java.time.format.DateTimeFormatter.ofPattern("M月d日"))
        else -> t.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/M/d"))
    }
}.getOrDefault("")
