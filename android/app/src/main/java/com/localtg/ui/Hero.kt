package com.localtg.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.localtg.AppContainer
import com.localtg.AppLog
import com.localtg.data.Item
import com.localtg.ui.tg.LocalSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 缩略图 ↔ 全屏查看器的"从哪里点开就从哪里展开"(小红书 / B 站 / Telegram 的做法),3.3.1 起替换原来的共享元素:
 *  - 打开:从内存缓存里取出点开前的那张缩略图(同步拿到,不用等加载),从它在列表里的位置用弹簧动画展开到全屏(按原图比例居中),
 *    圆角收成直角,背景同时渐暗;列表里那一格在查看器打开期间隐藏(看起来就是"被拿起来"了)。
 *    动画结束后查看器才接手 —— 它显示的第一帧就是同一张缩略图(placeholderMemoryCacheKey),再淡掉覆盖层,所以不会闪。
 *    以前闪一下的原因:共享元素在过渡期间把缩略图和查看器内容交叉淡化,查看器的图片 / 视频封面头几帧还是空的(异步加载),
 *    视频的 SurfaceView 又是在窗口上"挖洞"显示的,透过半透明的过渡能看到一块黑。
 *  - 返回:缩回到当前这一项在列表里的位置(查看器里翻到别的项,列表会跟着滚到那一项);不在屏幕上就缩小淡出。
 *    返回手势(Android 14+)时整个查看器跟着手指缩小、露出下面的列表,松手后接着缩回去,取消就弹回来。
 *  - 查看器是盖在列表上面的一层(不是导航的另一页):列表一直在,返回时不用重建,位置和格子都是现成的。
 * 「界面动画」选「关闭动画」时直接打开 / 关闭。
 */
class HeroState {
    /** 当前屏幕上每个缩略图的位置(根坐标)和圆角(px)。只给动画用,不触发重组。 */
    val bounds = HashMap<String, Pair<Rect, Float>>()

    /** 列表里要隐藏的那一格(查看器正在看的那一项)。 */
    var hiddenId by mutableStateOf<String?>(null)

    var phase by mutableStateOf(Phase.Idle)
    var image: ImageBitmap? = null
    var from = Rect.Zero
    var cornerFrom = 0f
    var to = Rect.Zero
    /** 0 = 在缩略图的位置,1 = 全屏。 */
    val k = Animatable(0f)
    /** 覆盖层整体透明度(交接给查看器时淡掉)。 */
    val overlay = Animatable(1f)
    /** 返回手势的进度 0 ~ 1(查看器跟着缩小)。 */
    var drag by mutableFloatStateOf(0f)

    enum class Phase { Idle, Opening, Handoff, Open, Closing }
}

val LocalHero = staticCompositionLocalOf<HeroState?> { null }

/** 查看器正在退出(返回手势进行中):视频页据此把画面截成位图盖上(SurfaceView 不会跟着缩放)。 */
val LocalViewerExiting = compositionLocalOf { false }

/** 标记一个缩略图:记下它的位置(打开 / 返回动画的起点和终点),查看器打开期间把正在看的那一项隐藏。 */
@Composable
fun Modifier.heroSource(id: String, corner: Dp = 0.dp): Modifier {
    val h = LocalHero.current ?: return this
    val c = with(LocalDensity.current) { corner.toPx() }
    DisposableEffect(h, id) { onDispose { h.bounds.remove(id) } }
    return this
        .onGloballyPositioned { h.bounds[id] = Rect(it.positionInRoot(), it.size.toSize()) to c }
        .graphicsLayer { alpha = if (h.hiddenId == id) 0f else 1f }
}

/** 内存缓存里这一项的缩略图(列表里刚显示过,一般都在)。 */
private fun memoryThumb(ctx: android.content.Context, item: Item): ImageBitmap? = runCatching {
    val img = coil3.SingletonImageLoader.get(ctx).memoryCache?.get(coil3.memory.MemoryCache.Key(thumbKey(item)))?.image
    (img as? coil3.BitmapImage)?.bitmap?.asImageBitmap()
}.getOrNull()

/** 按原图比例放进 W×H、居中(查看器里图片 / 视频封面的显示位置)。 */
private fun fitRect(item: Item, img: ImageBitmap?, w: Float, h: Float): Rect {
    val a = item.aspect?.takeIf { it > 0f } ?: img?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: (4f / 3f)
    val rw: Float
    val rh: Float
    if (a > w / h) { rw = w; rh = w / a } else { rh = h; rw = h * a }
    return Rect(Offset((w - rw) / 2, (h - rh) / 2), Size(rw, rh))
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun lerp(a: Rect, b: Rect, t: Float) = Rect(lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t))

/**
 * 聊天 / 搜索 / 随机页的根:[root] 是列表页,openViewer(index) 打开 c.viewerFeed 里的第 index 项。
 */
@Composable
fun ViewerNavHost(c: AppContainer, root: @Composable (openViewer: (Int) -> Unit) -> Unit) {
    val hero = remember { HeroState() }
    var index by remember { mutableIntStateOf(-1) } // -1 = 查看器没开(旋转屏幕不重建 Activity,不用存)
    var current by remember { mutableStateOf<Item?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val cfg = LocalSettings.current
    val animate = cfg.motion != Motion.OFF
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        fun finishClose() {
            index = -1; current = null
            hero.phase = HeroState.Phase.Idle; hero.hiddenId = null; hero.image = null; hero.drag = 0f
            AppLog.i("nav", "离开查看器")
        }

        val open: (Int) -> Unit = open@{ i ->
            val item = c.viewerFeed.items.getOrNull(i) ?: return@open
            if (hero.phase != HeroState.Phase.Idle) return@open
            AppLog.i("nav", "进入查看器 index=$i")
            current = item
            if (!animate) { index = i; hero.phase = HeroState.Phase.Open; return@open }
            // 全屏的图先开始加载(动画期间就在下载 / 解码),查看器接手时多半已经好了
            if (!item.isVideo && item.canShowNatively()) runCatching {
                coil3.SingletonImageLoader.get(ctx).enqueue(coil3.request.ImageRequest.Builder(ctx).data(c.api.imageUrl(item, cfg.viewerImageWidth)).build())
            }
            val img = memoryThumb(ctx, item)
            val src = hero.bounds[item.id]
            hero.image = img
            hero.to = fitRect(item, img, w, h)
            hero.from = src?.first ?: Rect(hero.to.center - Offset(hero.to.width * 0.3f, hero.to.height * 0.3f), Size(hero.to.width * 0.6f, hero.to.height * 0.6f))
            hero.cornerFrom = src?.second ?: 0f
            hero.hiddenId = item.id
            hero.phase = HeroState.Phase.Opening
            index = i
            scope.launch {
                hero.overlay.snapTo(1f)
                hero.k.snapTo(0f)
                hero.k.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 420f, visibilityThreshold = 0.001f))
                // 查看器开始组合;覆盖层(和它第一帧一模一样的图)再盖两帧,等它真正画出来再淡掉
                hero.phase = HeroState.Phase.Handoff
                withFrameNanos { }; withFrameNanos { }; withFrameNanos { }
                hero.overlay.animateTo(0f, tween(140))
                hero.phase = HeroState.Phase.Open
            }
        }

        fun close() {
            val cur = current
            if (index < 0) return
            if (!animate || cur == null) { finishClose(); return }
            when (hero.phase) {
                HeroState.Phase.Opening, HeroState.Phase.Handoff -> { // 打开动画还没完:等它完了再关
                    scope.launch { snapshotFlow { hero.phase }.first { it == HeroState.Phase.Open }; close() }
                    return
                }
                HeroState.Phase.Closing, HeroState.Phase.Idle -> return
                HeroState.Phase.Open -> {}
            }
            val img = memoryThumb(ctx, cur)
            val full = fitRect(cur, img, w, h)
            val s = 1f - 0.15f * hero.drag // 返回手势缩小到哪里,就从哪里接着缩回去
            hero.image = img
            hero.to = Rect(full.center - Offset(full.width * s / 2, full.height * s / 2), Size(full.width * s, full.height * s))
            val dst = hero.bounds[cur.id]
            hero.from = dst?.first ?: Rect(full.center - Offset(full.width * 0.3f, full.height * 0.3f), Size(full.width * 0.6f, full.height * 0.6f))
            hero.cornerFrom = dst?.second ?: 0f
            hero.hiddenId = cur.id
            hero.phase = HeroState.Phase.Closing // 查看器(含视频的 SurfaceView)马上拿掉,只剩覆盖层缩回去
            scope.launch {
                hero.overlay.snapTo(1f)
                hero.k.snapTo(1f)
                if (dst != null) hero.k.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 480f, visibilityThreshold = 0.001f))
                else { launch { hero.overlay.animateTo(0f, tween(200)) }; hero.k.animateTo(0f, tween(220)) }
                finishClose()
            }
        }

        CompositionLocalProvider(LocalHero provides hero) { root(open) }

        if (index >= 0) {
            // 返回手势:查看器跟着手指缩小(露出下面的列表),松手缩回列表,取消弹回
            PredictiveBackHandler(enabled = true) { progress ->
                try {
                    progress.collect { e -> if (hero.phase == HeroState.Phase.Open) hero.drag = e.progress }
                    close()
                } catch (e: CancellationException) {
                    val a = Animatable(hero.drag)
                    a.animateTo(0f, spring(stiffness = 600f)) { hero.drag = value }
                    throw e
                }
            }
            val showViewer = hero.phase == HeroState.Phase.Handoff || hero.phase == HeroState.Phase.Open
            if (showViewer) {
                CompositionLocalProvider(LocalHero provides null, LocalViewerExiting provides (hero.drag > 0f)) {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            val s = 1f - 0.15f * hero.drag
                            scaleX = s; scaleY = s
                            if (hero.drag > 0f) { shape = androidx.compose.foundation.shape.RoundedCornerShape((24 * hero.drag).dp); clip = true }
                        }.background(Color.Black)
                            // 查看器是盖在列表上的一层:没被查看器里的控件接住的触摸也不能漏到下面的列表
                            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                    ) {
                        ViewerScreen(c, startIndex = index, onBack = { close() }, onCurrent = { current = it; hero.hiddenId = it.id })
                    }
                }
            }
            if (hero.phase == HeroState.Phase.Opening || hero.phase == HeroState.Phase.Handoff || hero.phase == HeroState.Phase.Closing) HeroOverlay(hero)
        }
    }
}

/** 动画中的那张图:黑色背景随进度渐暗,图片从缩略图的位置 / 圆角插值到全屏,按"铺满裁剪"画(起点和列表格子一样,终点就是原图比例)。 */
@Composable
private fun HeroOverlay(h: HeroState) {
    val thumbColor = Color(0xFF2A2D33)
    Canvas(Modifier.fillMaxSize()) {
        val k = h.k.value
        val a = h.overlay.value
        val scrim = if (h.phase == HeroState.Phase.Closing) k.coerceIn(0f, 1f) * (1f - 0.4f * h.drag.coerceAtMost(1f)) else k.coerceIn(0f, 1f)
        drawRect(Color.Black, alpha = scrim * a)
        val r = lerp(h.from, h.to, k)
        if (r.width <= 1f || r.height <= 1f) return@Canvas
        val corner = lerp(h.cornerFrom, 0f, k.coerceIn(0f, 1f))
        val path = Path().apply { addRoundRect(RoundRect(r, CornerRadius(corner))) }
        clipPath(path) {
            val img = h.image
            if (img == null) { drawRect(thumbColor, r.topLeft, r.size, alpha = a); return@clipPath }
            val iw = img.width.toFloat()
            val ih = img.height.toFloat()
            val ra = r.width / r.height
            val sw: Float
            val sh: Float
            if (iw / ih > ra) { sh = ih; sw = ih * ra } else { sw = iw; sh = iw / ra }
            drawImage(
                img,
                srcOffset = IntOffset(((iw - sw) / 2).toInt(), ((ih - sh) / 2).toInt()), srcSize = IntSize(max(1, sw.toInt()), max(1, sh.toInt())),
                dstOffset = IntOffset(r.left.toInt(), r.top.toInt()), dstSize = IntSize(max(1, r.width.toInt()), max(1, r.height.toInt())),
                alpha = a, filterQuality = FilterQuality.Medium,
            )
        }
    }
}
