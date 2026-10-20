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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.drawBehind
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
    val bounds = HashMap<String, HeroSrc>()

    /** 列表所在的坐标系(背景缩放层里面的那一层):缩略图位置相对它取,背景怎么缩放都不影响记下的位置。 */
    var space: androidx.compose.ui.layout.LayoutCoordinates? = null

    /** 列表里要隐藏的那一格(查看器正在看的那一项)。 */
    var hiddenId by mutableStateOf<String?>(null)

    var phase by mutableStateOf(Phase.Idle)

    /** 打开进度:0 = 在缩略图的位置,1 = 全屏。背景的缩放 / 模糊 / 压暗、黑色背景也跟着它。 */
    val p = Animatable(0f)

    /** 系统预测性返回手势的进度 0 ~ 1(支持的系统才有)。 */
    var drag by mutableFloatStateOf(0f)

    /** 拖拽关闭:手指拖出的位移(图片跟着走),是否正在拖。 */
    var dragOffset by mutableStateOf(Offset.Zero)
    var dragging by mutableStateOf(false)
    var snapJob: kotlinx.coroutines.Job? = null

    /** 当前图片放大了(放大时拖动是平移图片,不是关闭)。 */
    var zoomed = false

    /** 返回动画的起点(拖拽 / 手势松手时图片的位置;null = 全屏居中)、背景和黑底从多少开始恢复。 */
    var closeFrom: Rect? = null
    var bgMul = 1f
    var backMul = 1f

    /** 返回动画开始前,视频页要先把画面截图盖住(SurfaceView 不跟着裁剪 / 缩放)。 */
    var exiting by mutableStateOf(false)
    var exitReady by mutableStateOf(false)

    /** 拖拽没到关闭的程度:弹回原位(途中可以再抓住)。 */
    fun snapBack(scope: kotlinx.coroutines.CoroutineScope) {
        snapJob?.cancel()
        val from = dragOffset
        snapJob = scope.launch {
            val a = Animatable(from, Offset.VectorConverter)
            a.animateTo(Offset.Zero, spring(dampingRatio = 0.85f, stiffness = 500f)) { dragOffset = value }
        }
    }

    enum class Phase { Idle, Opening, Open, Closing }
}

val LocalHero = staticCompositionLocalOf<HeroState?> { null }

/** 一个缩略图的位置:full = 完整的格子,visible = 被列表裁剪后看得见的部分(顶部被顶栏挡住的那截不算),corner = 圆角 px。 */
class HeroSrc(val full: Rect, val visible: Rect, val corner: Float)

private fun Rect.s() = "[%.0f,%.0f %.0fx%.0f]".format(left, top, width, height)

/**
 * 页面缩小时四周露出来的空隙填什么颜色("临近色"):空隙紧挨着哪部分就用哪部分的颜色 ——
 * 顶部和顶栏两侧用顶栏的颜色,下面用页面自己的背景(聊天背景渐变 / 网格底色 / 列表底色)。以前一律是白色,很突兀。
 * TgBar 报告自己的高度和颜色,页面报告下方的背景色。
 */
class BackdropSpec {
    var barHeight by mutableFloatStateOf(0f)
    var barColor by mutableStateOf(Color.Unspecified)
    var below by mutableStateOf<List<Color>>(emptyList())
}

val LocalBackdrop = staticCompositionLocalOf<BackdropSpec?> { null }

/** 画缩放内容后面的底:内容按 s 以中心缩小时,顶栏底边在屏幕上的位置以上用顶栏色,以下用页面背景色。 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBackdrop(spec: BackdropSpec, s: Float, fallback: Color) {
    val below = spec.below
    when {
        below.size >= 2 -> drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(below))
        below.size == 1 -> drawRect(below[0])
        else -> drawRect(fallback)
    }
    if (spec.barHeight > 0f && spec.barColor != Color.Unspecified) {
        val cy = size.height / 2
        val barBottom = cy + (spec.barHeight - cy) * s
        drawRect(spec.barColor, size = Size(size.width, barBottom.coerceAtLeast(0f)))
    }
}

/** 图片页报告自己有没有放大(条目 id, 是否放大):放大时上下拖动是平移图片,不触发拖拽关闭。 */
val LocalViewerZoomReport = staticCompositionLocalOf<(String, Boolean) -> Unit> { { _, _ -> } }

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
    // 列表铺在半透明的玻璃顶栏 / 底栏下面:被它们盖住的那截不算"看得见",打开动画从顶栏下沿开始展开,不会盖住标题栏
    val ins = com.localtg.ui.tg.LocalBarInsets.current
    val barTop = with(LocalDensity.current) { ins.calculateTopPadding().toPx() }
    val barBottom = with(LocalDensity.current) { ins.calculateBottomPadding().toPx() }
    DisposableEffect(h, id) { onDispose { h.bounds.remove(id) } }
    return this
        .onGloballyPositioned { lc ->
            val sp = h.space
            val ok = sp != null && sp.isAttached && lc.isAttached
            val full = if (ok) sp!!.localBoundingBoxOf(lc, clipBounds = false) else Rect(lc.positionInRoot(), lc.size.toSize())
            val vis0 = if (ok) sp!!.localBoundingBoxOf(lc, clipBounds = true) else full
            val spaceH = if (ok) sp!!.size.height.toFloat() else Float.MAX_VALUE
            val vis = vis0.intersect(Rect(vis0.left, barTop, vis0.right, spaceH - barBottom))
            val old = h.bounds[id]
            // 诊断:动画期间正在看的那一格在列表里动了(列表被滚动 / 重新排版)—— "抽动"多半就是这个
            if (old != null && id == h.hiddenId && h.phase != HeroState.Phase.Idle && (kotlin.math.abs(old.full.top - full.top) > 1f || kotlin.math.abs(old.full.left - full.left) > 1f)) {
                AppLog.i("anim", "格子移动 ${h.phase} ${old.full.s()} → ${full.s()}")
            }
            h.bounds[id] = HeroSrc(full, if (vis.width > 0f && vis.height > 0f) vis else full, c)
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
/** 打开途中被打断(还没展开完就返回):从当前位置、带着惯性收回。 */
private val CLOSE_SPRING = spring<Float>(dampingRatio = 1f, stiffness = 500f, visibilityThreshold = 0.001f)
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
 * 做法(照小红书 / B 站 / Telegram,docs/14):
 *  - **下一个界面从第一帧起就是加载好的真页面**:查看器一打开就组合,没有"先放替身图、动画完再换"的交接(以前闪屏的根源);
 *  - 动画中只裁出**图片本身**那块(从缩略图的位置 / 圆角变到全屏居中),黑色背景单独按进度淡入淡出 ——
 *    不再有一整块黑色圆角矩形在浅色列表上展开 / 收回(用户看到的"闪");
 *  - 列表跟着进度退后(缩小 + 模糊 + 压暗);返回时目标位置每一帧按背景当前的缩放换算,容器和格子同时落定;
 *  - **跟手**:按住图片上下拖,图片跟着手指走、越远越小,背景同步露出列表;松手按距离 / 速度决定关闭还是弹回。
 *    不依赖系统的预测性返回(很多国产 ROM 的侧滑返回只在松手时发一个"返回",不给进度);
 *  - **可打断**:打开没结束就返回,从当前位置直接收回;弹回途中可以再抓住;返回动画用 Animatable,随时接着当前值走。
 *  - 视频:动画期间不创建播放器(SurfaceView 不跟着裁剪和缩放),动画结束才开始播;返回前先截图盖住画面。视频页不做拖拽关闭(上下滑是亮度 / 音量)。
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
            hero.closeFrom = null; hero.bgMul = 1f; hero.backMul = 1f; hero.dragOffset = Offset.Zero; hero.dragging = false
            AppLog.i("nav", "离开查看器")
        }

        var openSeq by remember { mutableIntStateOf(0) } // 每次打开 +1:查看器重新创建(翻到的页从这次点的那一项开始)
        lateinit var openRef: (Int) -> Unit
        val open: (Int) -> Unit = open@{ i ->
            val item = c.viewerFeed.items.getOrNull(i) ?: return@open
            if (hero.phase == HeroState.Phase.Closing) {
                if (item.id == current?.id) {
                    // 关闭途中点回同一张:从当前位置原路展开回去(Animatable 接着当前值走,关闭协程被取消)
                    AppLog.i("nav", "关闭途中重新打开 index=$i")
                    AppLog.i("anim", "关闭途中重新打开 ${item.name} p=${"%.3f".format(hero.p.value)}")
                    hero.closeFrom = null; hero.bgMul = 1f; hero.backMul = 1f; hero.exiting = false; hero.exitReady = false
                    hero.phase = HeroState.Phase.Opening
                    scope.launch {
                        hero.p.animateTo(1f, OPEN_SPRING)
                        if (hero.phase == HeroState.Phase.Opening) hero.phase = HeroState.Phase.Open
                    }
                    return@open
                }
                // 点了别的:当前这个直接收尾,马上打开新的
                scope.launch { hero.p.snapTo(0f) }
                finishClose()
            }
            if (hero.phase != HeroState.Phase.Idle) return@open
            AppLog.i("nav", "进入查看器 index=$i")
            hero.bounds[item.id].let { b -> AppLog.i("anim", "打开 ${item.name} index=$i/${c.viewerFeed.items.size} 屏幕=${w.toInt()}x${h.toInt()} 格子=${b?.full?.s()} 可见=${b?.visible?.s()} 宽高比=${"%.3f".format(aspectOf(item, null))}") }
            current = item
            aspect = aspectOf(item, memoryThumb(ctx, item))
            hero.drag = 0f; hero.exiting = false; hero.exitReady = false
            hero.closeFrom = null; hero.bgMul = 1f; hero.backMul = 1f; hero.dragOffset = Offset.Zero
            if (!animate) { index = i; scope.launch { hero.p.snapTo(1f) }; hero.phase = HeroState.Phase.Open; return@open }
            if (!item.isVideo && item.canShowNatively()) runCatching {
                coil3.SingletonImageLoader.get(ctx).enqueue(coil3.request.ImageRequest.Builder(ctx).data(c.api.imageUrl(item, cfg.viewerImageWidth)).build())
            }
            hero.hiddenId = item.id
            hero.phase = HeroState.Phase.Opening
            index = i
            openSeq++
            scope.launch {
                hero.p.snapTo(0f)
                // 查看器第一次组合比较重:先组合、画出两帧再开始动,否则卡住的那一帧把动画前半段吃掉
                androidx.compose.runtime.withFrameNanos { }; androidx.compose.runtime.withFrameNanos { }
                hero.p.animateTo(1f, OPEN_SPRING)
                if (hero.phase == HeroState.Phase.Opening) hero.phase = HeroState.Phase.Open
            }
        }

        openRef = open

        fun close() {
            val cur = current
            if (index < 0) return
            if (!animate || cur == null) { scope.launch { hero.p.snapTo(0f) }; finishClose(); return }
            if (hero.phase == HeroState.Phase.Closing || hero.phase == HeroState.Phase.Idle) return
            val interrupt = hero.phase == HeroState.Phase.Opening // 打开没结束:从当前位置直接收回(Animatable 接着当前值走)
            AppLog.i("anim", "返回 ${cur.name} 打断打开=$interrupt p=${"%.3f".format(hero.p.value)} 拖拽起点=${hero.closeFrom?.s()} 格子=${hero.bounds[cur.id]?.full?.s()}")
            aspect = aspectOf(cur, memoryThumb(ctx, cur))
            hero.hiddenId = cur.id
            hero.snapJob?.cancel()
            scope.launch {
                if (cur.isVideo && !interrupt) { // 视频:先截图盖住 SurfaceView(最多等 150ms),再开始收回
                    hero.exiting = true
                    kotlinx.coroutines.withTimeoutOrNull(150) { snapshotFlow { hero.exitReady }.first { it } }
                }
                hero.phase = HeroState.Phase.Closing
                hero.dragging = false
                hero.p.animateTo(0f, if (interrupt) CLOSE_SPRING else CLOSE_TWEEN)
                finishClose()
            }
        }

        /** 拖拽松手,决定关闭:把当前拖到的位置作为返回动画的起点,背景从当前露出的程度接着恢复。 */
        fun closeFromDrag() {
            val fr = dragFrac(hero, full.height)
            hero.closeFrom = openImgRect(hero, aspect, full)
            hero.bgMul = 1f - fr
            hero.backMul = backdropOpen(fr)
            close()
        }

        // ---- 背景:列表跟着进度退后(缩小 + 模糊),返回 / 拖拽时恢复;完全收起时不加任何效果
        val pageBg = com.localtg.ui.tg.LocalTg.current.bg
        val backdrop = remember { BackdropSpec() }
        Box(
            Modifier.fillMaxSize().drawBehind { drawBackdrop(backdrop, 1f - BG_SCALE * bgFactor(hero, full.height), pageBg) }.graphicsLayer {
                val bp = bgFactor(hero, full.height)
                val s = 1f - BG_SCALE * bp
                scaleX = s; scaleY = s
                renderEffect = if (android.os.Build.VERSION.SDK_INT >= 31 && bp > 0.01f) {
                    val sigma = BG_BLUR * size.width * (bp / 0.8f).coerceAtMost(1f)
                    BlurEffect(sigma, sigma, TileMode.Clamp)
                } else null
            },
        ) {
            Box(Modifier.fillMaxSize().onGloballyPositioned { hero.space = it }) {
                CompositionLocalProvider(LocalHero provides hero, LocalBackdrop provides backdrop) { root(open) }
            }
        }

        // 诊断:动画 / 拖拽期间逐帧记录,用来分析"抽动"(格子在动 = 列表滚动;帧间隔大 = 卡顿;容器跳 = 位置算错)
        LaunchedEffect(hero.phase, hero.dragging) {
            if (hero.phase == HeroState.Phase.Idle || (hero.phase == HeroState.Phase.Open && !hero.dragging)) return@LaunchedEffect
            val ph = hero.phase
            val cur = current
            var t0 = 0L
            var last = 0L
            for (n in 0 until 150) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                if (t0 == 0L) { t0 = now; last = now }
                val dt = (now - last) / 1e6
                last = now
                val p = hero.p.value
                val box = containerState(hero, cur, aspect, full, p, CORNER_DP * density)?.first
                val img = imageState(hero, cur, aspect, full, p)
                val src = cur?.let { hero.bounds[it.id] }
                AppLog.i("anim", "%s %4.0fms 帧%5.1fms p=%.3f 背景=%.2f 拖=%s 容器=%s 图片=%s 格子=%s".format(
                    ph.name, (now - t0) / 1e6, dt, p, bgFactor(hero, full.height), hero.dragOffset.let { "%.0f,%.0f".format(it.x, it.y) },
                    box?.s(), img?.s(), src?.full?.s()))
                if (dt > 34) AppLog.i("anim", "卡顿 %.0fms".format(dt))
                if (hero.phase != ph && !(ph == HeroState.Phase.Open && hero.dragging)) break
            }
        }

        if (index >= 0) {
            // 查看器是黑底:打开期间状态栏 / 导航栏图标用浅色;开始收回就换回主题的颜色(白天主题的顶栏是白色,图标要深色)
            com.localtg.ui.tg.BarIconsEffect(if (hero.phase == HeroState.Phase.Closing) com.localtg.ui.tg.LocalTg.current.barIconsDark else false)
            // 系统的预测性返回(Android 14+、支持的系统才有进度):整个查看器跟着缩小;很多 ROM 只在松手时发返回,这时直接收回
            PredictiveBackHandler(enabled = true) { progress ->
                try {
                    progress.collect { e -> if (hero.phase == HeroState.Phase.Open && !hero.dragging) { hero.drag = e.progress; if (e.progress > 0f) hero.exiting = true } }
                    if (hero.drag > 0f) { hero.closeFrom = openImgRect(hero, aspect, full); hero.bgMul = 1f - dragFrac(hero, full.height); hero.backMul = backdropOpen(dragFrac(hero, full.height)) }
                    close()
                } catch (e: CancellationException) {
                    val a = Animatable(hero.drag)
                    a.animateTo(0f, spring(stiffness = 600f)) { hero.drag = value }
                    if (hero.phase == HeroState.Phase.Open) hero.exiting = false
                    throw e
                }
            }
            // 压暗(盖在退后的列表上)+ 查看器的黑色背景(按进度淡入淡出,拖拽时变淡露出列表)
            Canvas(Modifier.fillMaxSize()) {
                val bp = bgFactor(hero, full.height)
                drawRect(Color.Black, alpha = BG_DIM * bp)
                drawRect(Color.Black, alpha = backdropAlpha(hero, full.height))
            }

            val cur = current
            val cornerPx = CORNER_DP * density
            CompositionLocalProvider(
                LocalHero provides null,
                LocalViewerExiting provides (hero.exiting || hero.drag > 0f),
                LocalViewerShown provides (hero.phase == HeroState.Phase.Open && !hero.dragging),
                LocalViewerPlayerOk provides (hero.phase != HeroState.Phase.Opening),
                LocalViewerExitReady provides { id -> if (id == cur?.id) hero.exitReady = true },
                LocalViewerZoomReport provides { id, z -> if (id == cur?.id) hero.zoomed = z },
            ) {
                Box(
                    Modifier.fillMaxSize()
                        .drawWithContent {
                            val st = containerState(hero, cur, aspect, full, hero.p.value, cornerPx)
                            if (st == null) { drawContent(); return@drawWithContent }
                            val path = Path().apply { addRoundRect(RoundRect(st.first, CornerRadius(st.second))) }
                            clipPath(path) { this@drawWithContent.drawContent() }
                        }
                        // 拖拽关闭(只在图片页、没放大、已经打开时):在 Initial 阶段先看手势,竖直方向拖动才接管,横滑留给翻页,双指留给缩放
                        .pointerInput(Unit) {
                            val slop = viewConfiguration.touchSlop
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                if (hero.phase == HeroState.Phase.Closing) {
                                    down.consume()
                                    var moved = false
                                    while (true) {
                                        val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        ch.consume()
                                        if ((ch.position - down.position).getDistance() > slop) moved = true
                                        if (!ch.pressed) break
                                    }
                                    if (!moved) {
                                        val s = 1f - BG_SCALE * bgFactor(hero, full.height)
                                        val hit = hero.bounds.entries.firstOrNull { (_, b) -> b.visible.scaleAbout(s, full.center.x, full.center.y).contains(down.position) }?.key
                                        AppLog.i("anim", "关闭途中点击 ${down.position} → ${hit ?: "没点中缩略图"}")
                                        val idx = hit?.let { id -> c.viewerFeed.items.indexOfFirst { it.id == id } } ?: -1
                                        if (idx >= 0) openRef(idx)
                                    }
                                    return@awaitEachGesture
                                }
                                val canDrag = hero.phase == HeroState.Phase.Open && current?.isVideo == false && !hero.zoomed && hero.drag == 0f
                                if (!canDrag) return@awaitEachGesture
                                val vt = androidx.compose.ui.input.pointer.util.VelocityTracker()
                                vt.addPosition(down.uptimeMillis, down.position)
                                val start = hero.dragOffset // 弹回途中又抓住:接着当前位置
                                var total = Offset.Zero
                                var dragging = false
                                while (true) {
                                    val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    if (ev.changes.count { it.pressed } > 1) { // 第二根手指:交给缩放
                                        if (dragging) { hero.dragging = false; hero.snapBack(scope) }
                                        break
                                    }
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    vt.addPosition(ch.uptimeMillis, ch.position)
                                    if (!ch.pressed) {
                                        if (dragging) {
                                            val v = vt.calculateVelocity()
                                            val off = hero.dragOffset
                                            val far = kotlin.math.abs(off.y) > full.height * 0.08f
                                            val flick = kotlin.math.abs(v.y) > 900f && kotlin.math.sign(v.y) == kotlin.math.sign(off.y)
                                            if (far || flick) closeFromDrag() else { hero.dragging = false; hero.snapBack(scope) }
                                        }
                                        break
                                    }
                                    total += ch.positionChange()
                                    if (!dragging && total.getDistance() > slop) {
                                        if (kotlin.math.abs(total.y) > kotlin.math.abs(total.x) * 1.3f || start != Offset.Zero) {
                                            dragging = true
                                            hero.snapJob?.cancel()
                                            hero.dragging = true
                                        } else break // 横滑:交给翻页
                                    }
                                    if (dragging) {
                                        ch.consume()
                                        hero.dragOffset = start + total
                                    }
                                }
                            }
                        },
                ) {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            val img = imageState(hero, cur, aspect, full, hero.p.value)
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
                        },
                    ) {
                        androidx.compose.runtime.key(openSeq) {
                            ViewerScreen(c, startIndex = index, onBack = { close() }, onCurrent = { current = it; hero.hiddenId = it.id; aspect = aspectOf(it, memoryThumb(ctx, it)) })
                        }
                    }
                }
            }
        }
    }
}

/** 拖拽 / 系统返回手势让查看器"退出"了多少:0 = 没动,1 = 拖了半屏(或系统手势到底)。 */
private fun dragFrac(h: HeroState, H: Float): Float =
    if (h.drag > 0f) h.drag * 0.5f else (kotlin.math.abs(h.dragOffset.y) / (H * 0.5f)).coerceIn(0f, 1f)

/** 拖拽时黑色背景剩多少(拖得越远越淡,露出下面的列表)。 */
private fun backdropOpen(fr: Float) = (1f - fr * 1.6f).coerceIn(0f, 1f)

/** 黑色背景(查看器的底)的不透明度。 */
private fun backdropAlpha(h: HeroState, H: Float): Float = when (h.phase) {
    HeroState.Phase.Opening -> h.p.value.coerceIn(0f, 1f)
    HeroState.Phase.Closing -> h.p.value.coerceIn(0f, 1f) * h.backMul
    HeroState.Phase.Open -> backdropOpen(dragFrac(h, H))
    HeroState.Phase.Idle -> 0f
}

/** 背景(列表)退后的程度:缩放 / 模糊 / 压暗都按它。 */
private fun bgFactor(h: HeroState, H: Float): Float = when (h.phase) {
    HeroState.Phase.Opening -> h.p.value.coerceIn(0f, 1f)
    HeroState.Phase.Closing -> h.p.value.coerceIn(0f, 1f) * h.bgMul
    HeroState.Phase.Open -> 1f - dragFrac(h, H)
    HeroState.Phase.Idle -> 0f
}

/** 打开状态下(含拖拽 / 系统返回手势)图片的位置;null = 没在拖,不用变换。 */
private fun openImgRect(h: HeroState, aspect: Float, full: Rect): Rect? {
    val f = fitRect(aspect, full.width, full.height)
    val fr = dragFrac(h, full.height)
    val off = h.dragOffset
    if (fr <= 0f && off == Offset.Zero) return null
    val s = 1f - 0.35f * fr // 越拖越小(小红书 / Telegram 的手感)
    return f.scaleAbout(s, full.center.x, full.center.y).translate(off)
}

/** 缩略图在屏幕上当前的位置:记下的(列表坐标)按背景当前的缩放换算;找不到返回 null。 */
private fun sourceRect(h: HeroState, item: Item?, full: Rect): HeroSrc? {
    val b = item?.let { h.bounds[it.id] } ?: return null
    val s = 1f - BG_SCALE * bgFactor(h, full.height)
    val cx = full.center.x
    val cy = full.center.y
    return HeroSrc(b.full.scaleAbout(s, cx, cy), b.visible.scaleAbout(s, cx, cy), b.corner)
}

/** 动画的终点(打开)/ 起点(返回):全屏居中的图片,或拖拽松手时的位置。 */
private fun endRect(h: HeroState, aspect: Float, full: Rect): Rect =
    if (h.phase == HeroState.Phase.Closing) h.closeFrom ?: fitRect(aspect, full.width, full.height) else fitRect(aspect, full.width, full.height)

/** 裁剪框(只包住图片本身)和圆角;null = 不用裁剪(已经打开、没在拖)。 */
private fun containerState(h: HeroState, item: Item?, aspect: Float, full: Rect, p: Float, cornerPx: Float): Pair<Rect, Float>? {
    if (h.phase == HeroState.Phase.Open || h.phase == HeroState.Phase.Idle) {
        val r = openImgRect(h, aspect, full) ?: return null
        return r to cornerPx * (dragFrac(h, full.height) * 4f).coerceAtMost(1f)
    }
    val pc = p.coerceIn(0f, 1f)
    val end = endRect(h, aspect, full)
    val src = sourceRect(h, item, full)
    val from = src?.visible ?: end.scaleAbout(0.6f, end.center.x, end.center.y)
    val r = lerp(from, end, p)
    // 圆角:从缩略图的圆角很快变到 ~22dp,整个过程保持,最后 12% 收成直角(从拖拽返回时一直保持圆角)
    val base = lerp(src?.corner ?: cornerPx, cornerPx, (pc / 0.25f).coerceAtMost(1f))
    val corner = if (h.phase == HeroState.Phase.Closing && h.closeFrom != null) base else base * (1f - ((pc - 0.88f) / 0.12f).coerceIn(0f, 1f))
    return r to corner
}

/** 查看器里图片当前应该在的位置;null = 不用变换。 */
private fun imageState(h: HeroState, item: Item?, aspect: Float, full: Rect, p: Float): Rect? {
    if (h.phase == HeroState.Phase.Open || h.phase == HeroState.Phase.Idle) return openImgRect(h, aspect, full)
    val end = endRect(h, aspect, full)
    val src = sourceRect(h, item, full)
    val from = src?.let { coverRect(aspect, it.full) } ?: end.scaleAbout(0.6f, end.center.x, end.center.y)
    return lerp(from, end, p)
}
