package com.localtg.data

import java.util.Locale
import kotlin.math.ceil

const val DEFAULT_SLIDE_BAR_COLOR = "#CCCCCC"

fun slideBarThickness(value: Float): Float = if (value.isFinite()) value.coerceIn(0.5f, 12f) else 4f

/** Opaque RGB only, so a saved setting cannot accidentally hide the countdown. */
fun parseSlideBarColor(value: String): String? = value.trim().removePrefix("#")
    .takeIf { Regex("[0-9a-fA-F]{6}").matches(it) }?.let { "#${it.uppercase(Locale.ROOT)}" }

fun slideBarArgb(value: String): Long = 0xFF000000L or
    (parseSlideBarColor(value) ?: DEFAULT_SLIDE_BAR_COLOR).drop(1).toLong(16)

fun slideBarPosition(value: String): String = value.takeIf { it in setOf("top", "bottom", "left", "right") } ?: "top"
fun slidePhotoIndicator(value: String): String = value.takeIf { it in setOf("bar", "countdown", "none") } ?: "bar"

data class SlideBarBounds(val x: Float, val y: Float, val width: Float, val height: Float)

/** Horizontal bars shrink toward the left, vertical bars shrink toward the top. Dimensions are physical pixels. */
fun slideBarBounds(width: Float, height: Float, thickness: Float, position: String, remaining: Float): SlideBarBounds {
    val w = if (width.isFinite()) width.coerceAtLeast(0f) else 0f
    val h = if (height.isFinite()) height.coerceAtLeast(0f) else 0f
    val t = if (thickness.isFinite()) thickness.coerceIn(0f, minOf(w, h)) else 0f
    val fraction = if (remaining.isFinite()) remaining.coerceIn(0f, 1f) else 0f
    return when (slideBarPosition(position)) {
        "bottom" -> SlideBarBounds(0f, h - t, w * fraction, t)
        "left" -> SlideBarBounds(0f, 0f, t, h * fraction)
        "right" -> SlideBarBounds(w - t, 0f, t, h * fraction)
        else -> SlideBarBounds(0f, 0f, w * fraction, t)
    }
}

fun slideCountdownText(seconds: Float, remaining: Float): String {
    val fraction = if (remaining.isFinite()) remaining.coerceIn(0f, 1f) else 0f
    // Remove float noise at a tenth boundary without rounding down a visible part of a second.
    val tenths = ceil(slideSeconds(seconds).toDouble() * fraction.toDouble() * 10.0 - 0.00001).toInt().coerceAtLeast(0)
    return String.format(Locale.ROOT, "%.1f", tenths / 10f)
}
