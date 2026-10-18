package com.localtg.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.localtg.AppContainer
import com.localtg.AppLog
import com.localtg.data.Item
import com.localtg.ui.tg.LocalSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HeroState {
    /** 当前屏幕上每个缩略图的位置(列表自己的坐标系,不含背景退后时的缩放)和圆角(px)。只给动画用,不触发重组。 */
    val bounds = HashMap<String, Pair<Rect, Float>>()

    /** 列表所在的坐标系(背景缩放层里面的那一层):缩略图位置相对它取,背景怎么缩放都不影响记下的位置。 */
    var space: androidx.compose.ui.layout.LayoutCoordinates? = null

    /** 列表里要隐藏的那一格(查看器正在看的那一项)。 */
    var hiddenId by mutableStateOf<String?>(null)

    var phase by mutableStateOf(Phase.Idle)

    /** 容器完成度:0 = 在缩略图的位置,1 = 全屏。背景的缩放 / 模糊 / 压暗也跟着它。 */
    val p = Animatable(0f)

    /** 返回手势的进度 0 ~ 1(整个查看器跟着缩小)。 */
    var drag by mutableFloatStateOf(0f)

    /** 返回动画开始前,视频页要先把画面截图盖住(SurfaceView 不跟着裁剪 / 缩放)。 */
    var exiting by mutableStateOf(false)
    var exitReady by mutableStateOf(false)

    enum class Phase { Idle, Opening, Open, Closing }
}

val LocalHero = staticCompositionLocalOf<HeroState?> { null }

/** 查看器里的图片 / 视频页第一次把内容(哪怕是占位的缩略图)画出来时调用,参数是条目 id。 */
val LocalViewerReady = staticCompositionLocalOf<(String) -> Unit> { {} }

/** 视频页在退出前截好图、藏起 SurfaceView 后调用(参数是条目 id),返回动画收到后才开始。 */
val LocalViewerExitReady = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * 打开动画已经结束:顶栏 / 底栏这时才淡入滑入,视频这时才开始创建播放器(动画期间 SurfaceView 不跟着容器裁剪 / 缩放)。
 */
val LocalViewerShown = compositionLocalOf { true }

/** 打开动画还在进行时为 false:视频页这时不创建播放器(SurfaceView 不跟着容器裁剪 / 缩放),动画结束才开始播。 */
val LocalViewerPlayerOk = compositionLocalOf { true }

/** 查看器正在退出(返回手势进行中 / 返回动画):视频页据此把画面截成位图盖上(SurfaceView 不会跟着缩放)。 */
val LocalViewerExiting = compositionLocalOf { false }

/** 标记一个缩略图:记下它的位置(打开 / 返回动画的起点和终点),查看器打开期间把正在看的那一项隐藏。 */
@Composable
fun Modifier.heroSource(id: String, corner: Dp = 0.dp): Modifier {
    val h = LocalHero.current ?: return this
    val c = with(LocalDensity.current) { corner.toPx() }
    DisposableEffect(h, id) { onDispose { h.bounds.remove(id) } }
    return this
        .onGloballyPositioned { lc ->
            val sp = h.space
            val r = if (sp != null && sp.isAttached && lc.isAttached) sp.localBoundingBoxOf(lc, clipBounds = false) else Rect(lc.positionInRoot(), lc.size.toSize())
            h.bounds[id] = r to c
        }
        .graphicsLayer { alpha = if (h.hiddenId == id) 0f else 1f }
}

/**
 * 查看器每一页最底下垫的那张缩略图(从内存缓存同步取出,第一帧就有):大图 / 视频封面还在加载、解码时看到的是它而不是一片黑。
 * 返回有没有垫上(内存里没有这张缩略图时为 false)。
 */
@Composable
fun ViewerBaseThumb(item: Item, modifier: Modifier = Modifier): Boolean {
    val ctx = LocalContext.current
    val img = remember(item.id) { memoryThumb(ctx, item) } ?: return false
    androidx.compose.foundation.Image(img, null, modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit, filterQuality = FilterQuality.Medium)
    return true
}

/** 内存缓存里这一项的缩略图(列表里刚显示过,一般都在)。 */
internal fun memoryThumb(ctx: android.content.Context, item: Item): ImageBitmap? = runCatching {
    val img = coil3.SingletonImageLoader.get(ctx).memoryCache?.get(coil3.memory.MemoryCache.Key(thumbKey(item)))?.image
    (img as? coil3.BitmapImage)?.bitmap?.asImageBitmap()
}.getOrNull()

/** 原图宽高比(没有就用缩略图的,再没有按 4:3)。 */
private fun aspectOf(item: Item, img: ImageBitmap?): Float =
    item.aspect?.takeIf { it > 0f } ?: img?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: (4f / 3f)

/** 按宽高比 a 放进 W×H、居中(查看器里图片 / 视频封面的显示位置)。 */
private fun fitRect(a: Float, w: Float, h: Float): Rect {
    val rw: Float
    val rh: Float
    if (a > w / h) { rw = w; rh = w / a } else { rh = h; rw = h * a }
    return Rect(Offset((w - rw) / 2, (h - rh) / 2), Size(rw, rh))
}

/** 宽高比 a 的矩形"铺满"r(居中,超出部分会被裁掉):缩略图格子里图片实际画的位置。 */
private fun coverRect(a: Float, r: Rect): Rect {
    val rw: Float
    val rh: Float
    if (a > r.width / r.height) { rh = r.height; rw = rh * a } else { rw = r.width; rh = rw / a }
    return Rect(Offset(r.center.x - rw / 2, r.center.y - rh / 2), Size(rw, rh))
}

/** 以 (cx,cy) 为中心缩放一个矩形。 */
private fun Rect.scaleAbout(s: Float, cx: Float, cy: Float) = Rect(cx + (left - cx) * s, cy + (top - cy) * s, cx + (right - cx) * s, cy + (bottom - cy) * s)

/**
 * 逐帧量 B 站 / 小红书得到的曲线(docs/14):
 *  - 打开:前 1/3 时间走一半、约 300ms 走完 95%、没有过冲 → 弹簧 ζ≈1.2、刚度≈ω²≈420;
 *  - 返回:约 25ms 延迟后慢起步、中段近似匀速、再减速,约 340ms → cubic-bezier(0.30, 0.40, 0.40, 0.95)。
 */
private val OPEN_SPRING = spring<Float>(dampingRatio = 1.2f, stiffness = 420f, visibilityThreshold = 0.001f)
private val CLOSE_TWEEN = tween<Float>(durationMillis = 340, delayMillis = 25, easing = CubicBezierEasing(0.30f, 0.40f, 0.40f, 0.95f))

/** 背景(列表)随容器进度 p 退后:缩小到 0.905(以屏幕中心为中心)、模糊到屏宽的 2.6%、压暗 0.235。返回时反着来,都跟着 p 走。 */
private const val BG_SCALE = 0.095f
private const val BG_BLUR = 0.026f
private const val BG_DIM = 0.235f
private const val CORNER_DP = 22f

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun lerp(a: Rect, b: Rect, t: Float) = Rect(lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t))

/**
 * 聊天 / 搜索 / 随机页的根:[root] 是列表页,openViewer(index) 打开 c.viewerFeed 里的第 index 项。
 *
 * 做法(照小红书 / B 站,docs/14):**下一个界面从第一帧起就是加载好的真页面**,没有"先放一张替身图、动画完再换成真页面"的交接 ——
 * 以前闪屏就出在交接这一下。查看器一打开就组合,放在一个圆角容器里:
 *  - 容器的矩形从缩略图的位置变到全屏(四条边同一个进度 p),容器外面是退后的列表(缩小 + 模糊 + 压暗);
 *  - 容器里的查看器按全屏排版,整体缩放 / 平移,让查看器里图片的位置正好从缩略图(铺满裁剪)变到全屏居中;
 *    查看器底下垫的是内存里的缩略图(ViewerBaseThumb),大图没解码好之前也有画面;
 *  - 返回时反过来,容器朝缩略图收回;目标位置每一帧按背景当前的缩放实时换算(背景从 0.905 放大回 1 的同时容器落到格子上,不会最后跳一下);
 *  - 视频:动画期间不创建播放器(SurfaceView 不跟着裁剪和缩放),动画结束才开始播;返回前先把当前画面截图盖住。
 */
@Composable
fun ViewerNavHost(c: AppContainer, root: @Composable (openViewer: (Int) -> Unit) -> Unit) {
    val hero = remember { HeroState() }
    var index by remember { mutableIntStateOf(-1) } // -1 = 查看器没开(旋转屏幕不重建 Activity,不用存)
    var current by remember { mutableStateOf<Item?>(null) }
    var aspect by remember { mutableFloatStateOf(4f / 3f) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val cfg = LocalSettings.current
    val animate = cfg.motion != Motion.OFF
    val density = LocalDensity.current.density
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val full = Rect(0f, 0f, w, h)

        fun finishClose() {
            index = -1; current = null
            hero.phase = HeroState.Phase.Idle; hero.hiddenId = null; hero.drag = 0f
            hero.exiting = false; hero.exitReady = false
            AppLog.i("nav", "离开查看器")
        }

        val open: (Int) -> Unit = open@{ i ->
            val item = c.viewerFeed.items.getOrNull(i) ?: return@open
            if (hero.phase != HeroState.Phase.Idle) return@open
            AppLog.i("nav", "进入查看器 index=$i")
            current = item
            aspect = aspectOf(item, memoryThumb(ctx, item))
            hero.drag = 0f; hero.exiting = false; hero.exitReady = false
            if (!animate) { index = i; scope.launch { hero.p.snapTo(1f) }; hero.phase = HeroState.Phase.Open; return@open }
            // 全屏的图先开始加载(动画期间就在下载 / 解码)
            if (!item.isVideo && item.canShowNatively()) runCatching {
                coil3.SingletonImageLoader.get(ctx).enqueue(coil3.request.ImageRequest.Builder(ctx).data(c.api.imageUrl(item, cfg.viewerImageWidth)).build())
            }
            hero.hiddenId = item.id
            hero.phase = HeroState.Phase.Opening
            index = i
            scope.launch {
                hero.p.snapTo(0f)
                // 查看器第一次组合比较重(分页器 + 图片 / 播放器):先让它组合、画出两帧,再开始动。
                // 否则卡住的那一帧把动画前半段吃掉,看起来就是"一下子跳到快全屏"
                androidx.compose.runtime.withFrameNanos { }; androidx.compose.runtime.withFrameNanos { }
                hero.p.animateTo(1f, OPEN_SPRING)
                hero.phase = HeroState.Phase.Open
            }
        }

        fun close() {
            val cur = current
            if (index < 0) return
            if (!animate || cur == null) { scope.launch { hero.p.snapTo(0f) }; finishClose(); return }
            when (hero.phase) {
                HeroState.Phase.Opening -> { // 打开动画还没完:等它完了再关
                    scope.launch { snapshotFlow { hero.phase }.first { it == HeroState.Phase.Open }; close() }
                    return
                }
                HeroState.Phase.Closing, HeroState.Phase.Idle -> return
                HeroState.Phase.Open -> {}
            }
            aspect = aspectOf(cur, memoryThumb(ctx, cur))
            hero.hiddenId = cur.id
            scope.launch {
                // 视频:先截图盖住 SurfaceView(最多等 150ms),再开始收回
                if (cur.isVideo) {
                    hero.exiting = true
                    kotlinx.coroutines.withTimeoutOrNull(150) { snapshotFlow { hero.exitReady }.first { it } }
                }
                hero.phase = HeroState.Phase.Closing
                hero.p.animateTo(0f, CLOSE_TWEEN)
                finishClose()
            }
        }

        // ---- 背景:列表跟着容器进度 p 退后(缩小 + 模糊),返回时恢复;p=0 时不加任何效果
        val pageBg = com.localtg.ui.tg.LocalTg.current.bg
        Box(
            Modifier.fillMaxSize().background(pageBg).graphicsLayer {
                val p = hero.p.value.coerceIn(0f, 1f)
                val s = 1f - BG_SCALE * p
                scaleX = s; scaleY = s
                renderEffect = if (android.os.Build.VERSION.SDK_INT >= 31 && p > 0.01f) {
                    val sigma = BG_BLUR * size.width * (p / 0.8f).coerceAtMost(1f)
                    BlurEffect(sigma, sigma, TileMode.Clamp)
                } else null
            },
        ) {
            Box(Modifier.fillMaxSize().onGloballyPositioned { hero.space = it }) {
                CompositionLocalProvider(LocalHero provides hero) { root(open) }
            }
        }

        if (index >= 0) {
            // 返回手势:查看器跟着手指缩小(露出下面的列表),松手收回列表,取消弹回
            PredictiveBackHandler(enabled = true) { progress ->
                try {
                    progress.collect { e -> if (hero.phase == HeroState.Phase.Open) { hero.drag = e.progress; if (e.progress > 0f) hero.exiting = true } }
                    close()
                } catch (e: CancellationException) {
                    val a = Animatable(hero.drag)
                    a.animateTo(0f, spring(stiffness = 600f)) { hero.drag = value }
                    if (hero.phase == HeroState.Phase.Open) hero.exiting = false
                    throw e
                }
            }
            // 压暗:盖在退后的列表上(查看器打开后被全屏容器盖住)
            Canvas(Modifier.fillMaxSize()) { drawRect(Color.Black, alpha = BG_DIM * hero.p.value.coerceIn(0f, 1f)) }

            val cur = current
            val cornerPx = CORNER_DP * density
            CompositionLocalProvider(
                LocalHero provides null,
                LocalViewerExiting provides (hero.exiting || hero.drag > 0f),
                LocalViewerShown provides (hero.phase == HeroState.Phase.Open),
                LocalViewerPlayerOk provides (hero.phase != HeroState.Phase.Opening),
                LocalViewerExitReady provides { id -> if (id == cur?.id) hero.exitReady = true },
            ) {
                Box(
                    Modifier.fillMaxSize()
                        .drawWithContent {
                            val p = hero.p.value
                            val st = containerState(hero, cur, aspect, full, p, cornerPx)
                            if (st == null) { drawContent(); return@drawWithContent }
                            val path = Path().apply { addRoundRect(RoundRect(st.first, CornerRadius(st.second))) }
                            clipPath(path) {
                                drawRect(Color.Black, st.first.topLeft, st.first.size)
                                this@drawWithContent.drawContent()
                            }
                        }
                        // 查看器盖在列表上:没被查看器里的控件接住的触摸也不能漏到下面的列表
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                ) {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            val p = hero.p.value
                            val img = imageState(hero, cur, aspect, full, p)
                            val f = fitRect(aspect, full.width, full.height)
                            if (img == null || f.width <= 0f) {
                                scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f
                            } else {
                                val k = img.width / f.width
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                                scaleX = k; scaleY = k
                                translationX = img.left - k * f.left
                                translationY = img.top - k * f.top
                            }
                        }.background(Color.Black),
                    ) {
                        ViewerScreen(c, startIndex = index, onBack = { close() }, onCurrent = { current = it; hero.hiddenId = it.id; aspect = aspectOf(it, memoryThumb(ctx, it)) })
                    }
                }
            }
        }
    }
}

/** 返回手势时整个查看器缩小的比例(以屏幕中心为中心)。 */
private fun dragScale(h: HeroState) = 1f - 0.15f * h.drag

/** 缩略图在屏幕上当前的位置:记下的(列表坐标)按背景当前的缩放换算;找不到就用屏幕中间一个小框。 */
private fun sourceRect(h: HeroState, item: Item?, aspect: Float, full: Rect, p: Float): Pair<Rect, Float>? {
    val b = item?.let { h.bounds[it.id] } ?: return null
    val s = 1f - BG_SCALE * p.coerceIn(0f, 1f)
    return b.first.scaleAbout(s, full.center.x, full.center.y) to b.second
}

/** 容器当前的矩形和圆角;null = 不用裁剪(已经全屏、没有手势)。 */
private fun containerState(h: HeroState, item: Item?, aspect: Float, full: Rect, p: Float, cornerPx: Float): Pair<Rect, Float>? {
    val ds = dragScale(h)
    val to = if (ds < 1f) full.scaleAbout(ds, full.center.x, full.center.y) else full
    if (h.phase == HeroState.Phase.Open || h.phase == HeroState.Phase.Idle) {
        return if (h.drag > 0f) to to cornerPx * (h.drag * 4f).coerceAtMost(1f) else null
    }
    val pc = p.coerceIn(0f, 1f)
    val src = sourceRect(h, item, aspect, full, p)
    val from = src?.first ?: fitRect(aspect, full.width, full.height).scaleAbout(0.6f, full.center.x, full.center.y)
    val r = lerp(from, to, p)
    // 圆角:从缩略图的圆角很快变到 ~22dp,整个过程保持,最后 12% 才收成直角
    val base = lerp(src?.second ?: cornerPx, cornerPx, (pc / 0.25f).coerceAtMost(1f))
    val corner = base * (1f - ((pc - 0.88f) / 0.12f).coerceIn(0f, 1f))
    return r to corner
}

/** 查看器里图片当前应该在的位置;null = 不用变换。 */
private fun imageState(h: HeroState, item: Item?, aspect: Float, full: Rect, p: Float): Rect? {
    val f = fitRect(aspect, full.width, full.height)
    val ds = dragScale(h)
    val to = if (ds < 1f) f.scaleAbout(ds, full.center.x, full.center.y) else f
    if (h.phase == HeroState.Phase.Open || h.phase == HeroState.Phase.Idle) return if (h.drag > 0f) to else null
    val src = sourceRect(h, item, aspect, full, p)
    val from = src?.let { coverRect(aspect, it.first) } ?: f.scaleAbout(0.6f, full.center.x, full.center.y)
    return lerp(from, to, p)
}
