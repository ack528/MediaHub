package com.localtg.ui

import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { items[it].id },
            userScrollEnabled = !ui.locked && !videoSwipeSeek,
        ) { page ->
            val item = items[page]
            val isCurrent = pager.settledPage == page
            if (item.isVideo) {
                VideoPage(
                    item, c, isCurrent, ui, onBack = onBack,
                    onPrev = if (page > 0) { { scope.launch { pager.animateScrollToPage(page - 1) } } } else null,
                    onNext = if (page < items.lastIndex) { { scope.launch { pager.animateScrollToPage(page + 1) } } } else null,
                    onEnded = {
                        if (ui.sleepAt == -1L) { ui.sleepAt = 0L; AppLog.i("player", "睡眠定时:播完停止") }
                        else if (c.settings.value.autoNext && page < items.lastIndex) scope.launch { pager.animateScrollToPage(page + 1) }
                    },
                )
            } else {
                PhotoPage(item, c, ui, shared = pager.currentPage == page)
            }
        }
        // 图片的顶栏(视频页自带顶栏)
        if (!cur.isVideo) {
            AnimatedVisibility(
                ui.chrome && !inPip, Modifier.align(Alignment.TopStart),
                enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + slideOutVertically { -it / 2 },
            ) {
                Column(Modifier.fillMaxWidth().background(Color(0x66000000)).statusBarsPadding().padding(8.dp)) {
                    TextButton(onClick = onBack) { Text("‹ 返回", color = Color.White) }
                    Text(cur.name, color = Color.White, modifier = Modifier.padding(horizontal = 12.dp), maxLines = 1)
                    Text(
                        "${pager.currentPage + 1} / ${items.size}   ${formatDateTime(cur.takenAt)}   ${formatSize(cur.size)}" +
                            (cur.w?.let { "   ${cur.w}×${cur.h}" } ?: ""),
                        color = Color(0xCCFFFFFF), modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoPage(item: Item, c: AppContainer, ui: ViewerUi, shared: Boolean) {
    if (!item.canShowNatively()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂不支持预览 ${item.ext.uppercase()} 格式\n(服务端转换将在后续版本提供)", color = Color.White)
        }
        return
    }
    ZoomableAsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(c.api.fileUrl(item)).build(),
        contentDescription = item.name,
        modifier = Modifier.fillMaxSize().then(if (shared) Modifier.mediaShared(item.id, RectangleShape) else Modifier),
        onClick = { ui.chrome = !ui.chrome },
    )
}
