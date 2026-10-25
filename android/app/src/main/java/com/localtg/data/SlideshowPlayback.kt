package com.localtg.data

import androidx.compose.runtime.*

/** Shared by photo controls, video controls, lifecycle and the menu. */
class SlideshowPlayback {
    var options by mutableStateOf<SlideshowOptions?>(null); private set
    var paused by mutableStateOf(false); private set
    var finished by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set
    var preparing by mutableStateOf(false)
    var message by mutableStateOf(""); private set
    var epoch by mutableIntStateOf(0); private set
    var countdownRevision by mutableIntStateOf(0); private set
    private var resumeAfterFailure = false
    val active get() = options != null
    val playing get() = active && !paused && !finished && !failed
    fun start(value: SlideshowOptions) { options = value.copy(imageSeconds = slideSeconds(value.imageSeconds)); countdownRevision++; paused = false; finished = false; failed = false; message = ""; epoch++ }
    fun canAdvance(menu: Boolean = false, touching: Boolean = false, scrolling: Boolean = false, zoomed: Boolean = false) = playing && !preparing && !menu && !touching && !scrolling && !zoomed
    fun pause(text: String? = null) { if (active) { paused = true; if (failed) resumeAfterFailure = false; if (text != null) message = text } }
    fun resume() { if (active && !finished && !failed) { paused = false; message = ""; epoch++ } }
    fun stop() { options = null; paused = false; finished = false; failed = false; preparing = false; message = ""; epoch++ }
    fun fail(text: String) { if (active) { if (!failed) resumeAfterFailure = playing; failed = true; paused = true; message = text } }
    fun ready() { if (active && failed) { failed = false; paused = !resumeAfterFailure; message = ""; epoch++ } }
    fun end() { if (active) { finished = true; paused = true; message = "本轮已播放完毕" } }
    fun interval(seconds: Float) { options = options?.copy(imageSeconds = slideSeconds(seconds)); countdownRevision++; epoch++ }
}
