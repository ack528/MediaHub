/*
 * 液态玻璃的交互动效。移植自 Kyant0/AndroidLiquidGlass 的示例程序(Backdrop Catalog)
 * 中的 DampedDragAnimation / InteractiveHighlight / inspectDragGestures / LiquidButton,
 * 原作者 Kyant,Apache License 2.0(https://github.com/Kyant0/AndroidLiquidGlass)。
 * 改动:去掉 Compose Multiplatform 的 expect/actual(awaitFrame → withFrameNanos、kotlin.time.Clock → SystemClock),
 * 加了中文注释,LiquidButton 的按压形变抽成了 [liquidPress]。
 */
package com.localtg.ui.glass

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/** 只观察拖动、不消费事件的手势(按下 / 拖动 / 抬起都通知),点击仍然交给 clickable 处理。 */
suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)
        val down = awaitFirstDown(false)
        onDragStart(down)
        onDrag(initialDown, Offset.Zero)
        val upEvent = dragUntilUp(initialDown.id) { onDrag(it, it.positionChange()) }
        if (upEvent == null) onDragCancel() else onDragEnd(upEvent)
    }
}

private suspend inline fun AwaitPointerEventScope.dragUntilUp(pointerId: PointerId, onDrag: (PointerInputChange) -> Unit): PointerInputChange? {
    if (currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true) return null
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) return change
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(pointerId: PointerId): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed } ?: return dragEvent
            pointer = otherDown.id
        } else if (dragEvent.previousPosition != dragEvent.position) {
            return dragEvent
        }
    }
}

/**
 * 带阻尼的拖动值:底栏的选中块(puck)用它在标签之间滑动。按下时 pressProgress 0→1、块放大(X / Y 用不同阻尼,像果冻);
 * 滑动时按速度在水平方向拉伸、垂直方向压扁;松手后弹回目标位置再缩回去。
 */
class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    initialValue: Float,
    val valueRange: ClosedRange<Float>,
    visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {
    private val valueSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocitySpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressSpec = spring(1f, 1000f, 0.001f)
    private val scaleXSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)
    private val mutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down -> onDragStarted(down.position); press() },
            onDragEnd = { onDragStopped(); release() },
            onDragCancel = { onDragStopped(); release() },
        ) { _, dragAmount -> onDrag(size, dragAmount) }
    }

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressAnimation.animateTo(1f, pressSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            withFrameNanos { }
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }.filter { abs(it - valueAnimation.targetValue) < threshold }.first()
            }
            launch { pressAnimation.animateTo(0f, pressSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYSpec) }
        }
    }

    fun updateValue(value: Float) {
        val t = value.coerceIn(valueRange)
        animationScope.launch { valueAnimation.animateTo(t, valueSpec) { updateVelocity() } }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutex.mutate {
                press()
                val t = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(t, valueSpec) }
                if (velocity != 0f) launch { velocityAnimation.animateTo(0f, velocitySpec) }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(SystemClock.uptimeMillis(), Offset(value, 0f))
        val v = velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(v, velocitySpec) }
    }
}

/**
 * 按压高光:手指按下的位置亮起一团柔光(Android 13+ 用着色器画径向光斑,更早的系统整块提亮),跟着手指走,松手后弹回、熄灭。
 * [modifier] 负责画光,[gestureModifier] 负责跟踪手指。
 */
class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
) {
    private val pressSpec = spring(0.5f, 300f, 0.001f)
    private val positionSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)
    private val pressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private var startPosition = Offset.Zero
    val pressProgress: Float get() = pressAnimation.value
    val offset: Offset get() = positionAnimation.value - startPosition

    private val shader = if (android.os.Build.VERSION.SDK_INT >= 33) android.graphics.RuntimeShader(
        """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;
half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}""",
    ) else null

    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressAnimation.value
        if (progress > 0f) {
            val sh = shader
            if (sh != null && android.os.Build.VERSION.SDK_INT >= 33) {
                drawRect(Color.White.copy(0.08f * progress), blendMode = BlendMode.Plus)
                val p = position(size, positionAnimation.value)
                sh.setFloatUniform("size", size.width, size.height)
                sh.setColorUniform("color", android.graphics.Color.valueOf(1f, 1f, 1f, 0.15f * progress).toArgb())
                sh.setFloatUniform("radius", size.minDimension * 1.5f)
                sh.setFloatUniform("position", p.x.fastCoerceIn(0f, size.width), p.y.fastCoerceIn(0f, size.height))
                drawRect(ShaderBrush(sh), blendMode = BlendMode.Plus)
            } else {
                drawRect(Color.White.copy(0.20f * progress), blendMode = BlendMode.Plus)
            }
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope) {
        inspectDragGestures(
            onDragStart = { down ->
                startPosition = down.position
                animationScope.launch {
                    launch { pressAnimation.animateTo(1f, pressSpec) }
                    launch { positionAnimation.snapTo(startPosition) }
                }
            },
            onDragEnd = {
                animationScope.launch {
                    launch { pressAnimation.animateTo(0f, pressSpec) }
                    launch { positionAnimation.animateTo(startPosition, positionSpec) }
                }
            },
            onDragCancel = {
                animationScope.launch {
                    launch { pressAnimation.animateTo(0f, pressSpec) }
                    launch { positionAnimation.animateTo(startPosition, positionSpec) }
                }
            },
        ) { change, _ -> animationScope.launch { positionAnimation.snapTo(change.position) } }
    }
}

/**
 * 液态玻璃按钮的按压形变(LiquidButton):按下时整体放大一点,手指拖动时朝拖动方向"被拉过去"并沿该方向拉长,
 * 松手后弹簧弹回;同时画按压高光。放在玻璃部件的修饰链最前面。
 */
fun Modifier.liquidPress(h: InteractiveHighlight): Modifier =
    this.graphicsLayer {
        val w = size.width
        val ht = size.height
        if (w <= 0f || ht <= 0f) return@graphicsLayer
        val progress = h.pressProgress
        val scale = lerp(1f, 1f + 4f.dp.toPx() / ht, progress)
        val maxOffset = size.minDimension
        val k = 0.05f
        val o = h.offset
        translationX = maxOffset * tanh(k * o.x / maxOffset)
        translationY = maxOffset * tanh(k * o.y / maxOffset)
        val maxDrag = 4f.dp.toPx() / ht
        val angle = atan2(o.y, o.x)
        scaleX = scale + maxDrag * abs(cos(angle) * o.x / size.maxDimension) * (w / ht).fastCoerceAtMost(1f)
        scaleY = scale + maxDrag * abs(sin(angle) * o.y / size.maxDimension) * (ht / w).fastCoerceAtMost(1f)
    }.then(h.modifier).then(h.gestureModifier)
