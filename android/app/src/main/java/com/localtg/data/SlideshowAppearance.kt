package com.localtg.data

import java.util.Locale

const val DEFAULT_SLIDE_BAR_COLOR = "#CCCCCC"

fun slideBarThickness(value: Float): Float = if (value.isFinite()) value.coerceIn(0.5f, 12f) else 4f

/** Opaque RGB only, so a saved setting cannot accidentally hide the countdown. */
fun parseSlideBarColor(value: String): String? = value.trim().removePrefix("#")
    .takeIf { Regex("[0-9a-fA-F]{6}").matches(it) }?.let { "#${it.uppercase(Locale.ROOT)}" }

fun slideBarArgb(value: String): Long = 0xFF000000L or
    (parseSlideBarColor(value) ?: DEFAULT_SLIDE_BAR_COLOR).drop(1).toLong(16)
