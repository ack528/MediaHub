package com.localtg.ui

import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.localtg.ui.tg.Pill
import com.localtg.ui.tg.TgIcons
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.request.ImageRequest
import com.localtg.AppContainer
import com.localtg.AppLog
import com.localtg.data.Item
import com.localtg.ui.tg.LocalSettings
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage

/** 全屏查看器:左右滑动切换;图片可缩放;视频自动开始播放(当前页),带宽只在翻到该页时才使用。 */
@Composable
fun ViewerScreen(c: AppContainer, startIndex: Int, onBack: () -> Unit) {
    val feed = c.viewerFeed
    val items = feed.items
    if (items.isEmpty()) { onBack(); return }
    val cfg = LocalSettings.current
    val ctx = LocalContext.current
    val act = remember(ctx) { ctx.findActivity() }
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) { items.size }
    val ui = remember { ViewerUi() }
    val scope = rememberCoroutineScope()
    val inPip by c.inPip.collectAsState()
    val cur = items[pager.currentPage]
    // 翻到接近已加载内容的末尾时,继续取更多(聊天历史 / 搜索结果),不会只能翻到第一页
    LaunchedEffect(pager.currentPage, items.size) { if (pager.currentPage >= items.size - 5) feed.loadMore() }
    LaunchedEffect(cur.id) { AppLog.i("viewer", "查看 ${cur.name} (${cur.type}, ${pager.currentPage + 1}/${items.size})") }
    // 每滑到一项就记下来:退出查看器后,群组页定位到最后停留的这一项(不是打开时的那一项)
    LaunchedEffect(cur.id) { feed.originDialogId?.let { c.viewerReturn.value = it to cur.id } }

    // 控制条隐藏 → 沉浸模式(隐藏状态栏和导航栏);离开时恢复
    DisposableEffect(ui.chrome, inPip) {
        val w = act?.window
        if (w != null) {
            val ctl = WindowCompat.getInsetsController(w, w.decorView)
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (!ui.chrome || inPip) ctl.hide(WindowInsetsCompat.Type.systemBars()) else ctl.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            act?.window?.let { w -> WindowCompat.getInsetsController(w, w.decorView).show(WindowInsetsCompat.Type.systemBars()) }
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    // 横屏视频自动转横屏:翻到别的页时恢复
    LaunchedEffect(cur.id) { if (ui.autoRotated) { ui.autoRotated = false; ui.orientation = 0 } }
    // 屏幕方向(0 自动 / 1 横屏 / 2 竖屏)
    DisposableEffect(ui.orientation) {
        act?.requestedOrientation = when (ui.orientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { }
    }

    // VLC 方式:视频页水平滑动 = 快进快退,此时翻页靠上一个 / 下一个按钮
    val videoSwipeSeek = cur.isVideo && cfg.gestures && cfg.swipeSeek

    // 打开的转场动画期间只加载当前这一页,动画结束后再预加载左右页:三张大图同时解码 / 上传 GPU 会让转场掉帧(看起来就是闪 / 卡一下)
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(600); settled = true }
    // 翻页方向:向右滑 = 上一个(设置里可改)。列表序号和屏幕上的先后可能相反(聊天流最新在底部),所以按"上一个是不是序号更大的一项"决定
    val prevDelta = if (feed.prevIsHigherIndex) 1 else -1
    val reverse = feed.prevIsHigherIndex == cfg.rightSwipePrev
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            pager, Modifier.fillMaxSize(), beyondViewportPageCount = if (settled) cfg.viewerPreload else 0, key = { items[it].id }, reverseLayout = reverse,
            userScrollEnabled = !ui.locked && !videoSwipeSeek,
        ) { page ->
            val item = items[page]
            val isCurrent = pager.settledPage == page
            if (item.isVideo) {
                VideoPage(
                    item, c, isCurrent, ui, onBack = onBack,
                    onPrev = if (page + prevDelta in items.indices) { { scope.launch { pager.animateScrollToPage(page + prevDelta) } } } else null,
                    onNext = if (page - prevDelta in items.indices) { { scope.launch { pager.animateScrollToPage(page - prevDelta) } } } else null,
                    onEnded = {
                        if (ui.sleepAt == -1L) { ui.sleepAt = 0L; AppLog.i("player", "睡眠定时:播完停止") }
                        else if (c.settings.value.autoNext && page - prevDelta in items.indices) scope.launch { pager.animateScrollToPage(page - prevDelta) }
                    },
                )
            } else {
                PhotoPage(item, c, ui, shared = pager.currentPage == page)
            }
        }
        // 图片的顶栏 / 底栏:和视频页同一套风格(渐变底、图标按钮、小字号),不再是一整块半透明黑条
        if (!cur.isVideo) {
            var panel by remember { mutableStateOf("") } // 右上角菜单(和视频页同一套):"" 关闭 / main / info
            LaunchedEffect(cur.id) { panel = "" }
            AnimatedVisibility(
                ui.chrome && !inPip, Modifier.align(Alignment.TopStart),
                enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + slideOutVertically { -it / 2 },
            ) {
                Row(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViewerIcon(TgIcons.Back, "返回") { onBack() }
                    Text(
                        cur.name, Modifier.weight(1f).padding(horizontal = 4.dp), color = Color.White, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    ViewerIcon(TgIcons.More, "更多") { panel = if (panel.isEmpty()) "main" else "" }
                }
            }
            AnimatedVisibility(
                ui.chrome && !inPip, Modifier.align(Alignment.BottomStart),
                enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                Row(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                        .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(formatDateTime(cur.takenAt), color = Color.White, fontSize = 14.sp)
                        Text(
                            listOfNotNull(cur.w?.let { "${cur.w}×${cur.h}" }, formatSize(cur.size), cur.ext.uppercase().takeIf { it.isNotEmpty() }).joinToString(" · "),
                            color = Color(0xB3FFFFFF), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Pill("${pager.currentPage + 1} / ${items.size}", sizeSp = 12)
                }
            }
            val lastPage = remember { arrayOf("main") }
            if (panel.isNotEmpty()) lastPage[0] = panel
            val page = panel.ifEmpty { lastPage[0] }
            PlayerMenuHost(
                open = panel.isNotEmpty() && !inPip,
                title = if (page == "info") "图片信息" else null,
                onBack = { panel = "main" },
                onClose = { panel = "" },
            ) {
                if (page == "info") {
                    photoInfoRows(cur).forEach { (k, v) -> MenuInfo(k, v) }
                } else {
                    if (!c.isLocal && cur.size > 0L && !cur.flags.corrupt) {
                        MenuRow("保存到手机", arrow = false) { panel = ""; scope.launch { com.localtg.data.saveToPhoneWithToast(ctx, c.http, c.api, cur) } }
                    }
                    MenuRow("图片信息") { panel = "info" }
                }
            }
        }
    }
}

/** 顶栏上的圆形图标按钮(和视频页的控制按钮同尺寸)。 */
@Composable
private fun ViewerIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

private fun photoInfoRows(item: Item): List<Pair<String, String>> = listOfNotNull(
    "名称" to item.name,
    "格式" to listOf(item.ext.uppercase(), item.mime).filter { it.isNotEmpty() }.joinToString(" · "),
    item.w?.let { "尺寸" to "${item.w} × ${item.h}" },
    "大小" to "${formatSize(item.size)}(${"%,d".format(item.size)} 字节)",
    formatDateTime(item.takenAt).takeIf { it.isNotEmpty() }?.let { "拍摄" to it },
    formatDateTime(item.modifiedAt).takeIf { it.isNotEmpty() }?.let { "修改" to it },
    formatDateTime(item.createdAt).takeIf { it.isNotEmpty() }?.let { "创建" to it },
).filter { it.second.isNotEmpty() }

@Composable
private fun PhotoPage(item: Item, c: AppContainer, ui: ViewerUi, shared: Boolean) {
    if (!item.canShowNatively()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂不支持预览 ${item.ext.uppercase()} 格式\n(服务端转换将在后续版本提供)", color = Color.White)
        }
        return
    }
    val cfg = LocalSettings.current
    ZoomableAsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(c.api.imageUrl(item, cfg.viewerImageWidth))
            .placeholderMemoryCacheKey(thumbKey(item)) // 先显示点开前的缩略图,不是一片黑
            .build(),
        contentDescription = item.name,
        state = me.saket.telephoto.zoomable.rememberZoomableImageState(
            me.saket.telephoto.zoomable.rememberZoomableState(zoomSpec = me.saket.telephoto.zoomable.ZoomSpec(maxZoomFactor = cfg.maxZoom)),
        ),
        modifier = Modifier.fillMaxSize().then(if (shared) Modifier.mediaShared(item.id, RectangleShape) else Modifier),
        onClick = { ui.chrome = !ui.chrome },
    )
}
