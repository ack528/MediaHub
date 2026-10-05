package com.localtg.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.provider.Settings
import android.util.Rational
import android.widget.Toast
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.localtg.AppContainer
import com.localtg.AppLog
import com.localtg.data.Item
import com.localtg.ui.tg.LocalSettings
import com.localtg.ui.tg.TgIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** 查看器级别的界面状态,所有页共享(控制条显示、锁定、方向、睡眠定时)。 */
class ViewerUi {
    var chrome by mutableStateOf(true)
    var locked by mutableStateOf(false)
    /** 0 = 跟随系统,1 = 横屏,2 = 竖屏 */
    var orientation by mutableIntStateOf(0)
    /** 当前的横屏是"横向视频自动转横屏"设置触发的(翻页时要恢复) */
    var autoRotated = false
    /** 睡眠定时:0 = 未设置,-1 = 当前视频播完后停止,>0 = elapsedRealtime 到点停止 */
    var sleepAt by mutableLongStateOf(0L)
}

/** 按设置给字幕上样式:大小、描边 / 阴影 / 黑底、离底部的距离。 */
private fun styleSubtitles(v: PlayerView, cfg: com.localtg.data.AppSettings) {
    val sv = v.subtitleView ?: return
    if (cfg.subtitleScale == 1f) sv.setUserDefaultTextSize() else sv.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * cfg.subtitleScale)
    val white = android.graphics.Color.WHITE
    val clear = android.graphics.Color.TRANSPARENT
    when (cfg.subtitleStyle) {
        "outline" -> sv.setStyle(androidx.media3.ui.CaptionStyleCompat(white, clear, clear, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null))
        "shadow" -> sv.setStyle(androidx.media3.ui.CaptionStyleCompat(white, clear, clear, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, android.graphics.Color.BLACK, null))
        "box" -> sv.setStyle(androidx.media3.ui.CaptionStyleCompat(white, 0xB0000000.toInt(), clear, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE, white, null))
        "yellow" -> sv.setStyle(androidx.media3.ui.CaptionStyleCompat(0xFFFFE600.toInt(), clear, clear, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null))
        else -> sv.setUserDefaultStyle()
    }
    sv.setBottomPaddingFraction(cfg.subtitleBottom / 100f)
}

internal fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

private data class TrackOpt(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)
private data class Hud(val icon: ImageVector?, val text: String, val progress: Float? = null)

private fun langName(l: String?): String? =
    if (l.isNullOrEmpty() || l == "und") null else Locale.forLanguageTag(l).getDisplayLanguage(Locale.CHINESE).ifEmpty { l }

private fun codecName(mime: String?): String? = mime?.substringAfter('/')?.removePrefix("x-")?.uppercase()

private fun trackOpts(tracks: Tracks, type: Int): List<TrackOpt> {
    val out = mutableListOf<TrackOpt>()
    var n = 0
    tracks.groups.filter { it.type == type }.forEach { g ->
        for (i in 0 until g.length) {
            val f = g.getTrackFormat(i)
            n++
            val parts = listOfNotNull(
                f.label, langName(f.language), codecName(f.sampleMimeType ?: f.codecs),
                if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) "${f.channelCount}ch" else null,
                if (type == C.TRACK_TYPE_VIDEO && f.height > 0) "${f.height}p" else null,
            ).distinct()
            out += TrackOpt(g, i, parts.joinToString(" · ").ifEmpty { "轨道 $n" }, g.isTrackSelected(i))
        }
    }
    return out
}

/** 视频分辨率(短边像素)对应的默认转码码率(bps)。转码不改分辨率,只按这个码率压缩。 */
private fun bitrateFor(h: Int): Int = when {
    h <= 480 -> 1_500_000
    h <= 720 -> 3_000_000
    h <= 1080 -> 6_000_000
    h <= 1440 -> 12_000_000
    else -> 20_000_000
}

/** 转码码率:设置里指定了就用,否则按原视频分辨率自动。 */
private fun autoBitrate(item: Item, s: com.localtg.data.AppSettings): Int =
    if (s.maxBitrateMbps > 0) s.maxBitrateMbps * 1_000_000
    else bitrateFor(minOf(item.w ?: 1920, item.h ?: 1080).let { if (it <= 0) 1080 else it })

private fun fmt(ms: Long): String {
    val s = (ms.coerceAtLeast(0)) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** 视频页:操作方式参照 VLC —— 左半屏上下滑调亮度、右半屏上下滑调音量、水平滑动快进快退、
 *  双击两侧 ±N 秒(连续点击累加)、双击中间暂停、长按倍速、双指捏合切换画面比例。 */
@Composable
fun VideoPage(
    item: Item, c: AppContainer, isCurrent: Boolean, ui: ViewerUi,
    onBack: () -> Unit, onPrev: (() -> Unit)?, onNext: (() -> Unit)?, onEnded: () -> Unit,
) {
    val ctx = LocalContext.current
    val act = remember(ctx) { ctx.findActivity() }
    val cfg = LocalSettings.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val inPip by c.inPip.collectAsState()

    val ns = remember { c.session.ns() }
    val base0 = remember { c.session.session.value?.baseUrl } // 打开时是哪台服务器(进度要同步回它)
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var firstFrame by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var useSoftware by remember(item.id) { mutableStateOf(false) }       // 硬解失败后自动改软解重试一次
    var modeOverride by remember(item.id) { mutableStateOf<String?>(null) } // 用户手动指定解码方式
    var forceTry by remember(item.id) { mutableStateOf(false) } // 服务端判定文件损坏时,用户选择"仍然尝试播放"
    val blocked = item.brokenLabel() != null && (item.size == 0L || !forceTry)
    var transcodeBitrate by remember(item.id) { mutableStateOf<Int?>(null) } // null = 直接播放原文件;否则请服务端按这个码率(bps)转码,分辨率不变
    val sid = remember(item.id) { java.util.UUID.randomUUID().toString().take(8) }
    var resumeMs by remember(item.id) { mutableLongStateOf(-1L) }        // -1 = 还没读取保存的进度
    var stats by remember { mutableStateOf("") }
    var enhStats by remember { mutableStateOf("") }
    var frcText by remember { mutableStateOf("") }
    var enhView by remember { mutableStateOf<com.localtg.render.EnhancedVideoView?>(null) }
    val decoderName = remember { arrayOf("") }
    val dropped = remember { longArrayOf(0) }

    // 播放状态(监听器更新)
    var playing by remember { mutableStateOf(false) }
    var state by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var speed by remember { mutableFloatStateOf(cfg.speed) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var loop by remember { mutableStateOf(cfg.loop) }
    var resize by remember(cfg.resizeMode) { mutableStateOf(cfg.resizeMode) }
    var abA by remember(item.id) { mutableStateOf<Long?>(null) }
    var abB by remember(item.id) { mutableStateOf<Long?>(null) }
    var hud by remember { mutableStateOf<Hud?>(null) }
    var hudTick by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf("") }   // audio / text / speed / sleep / jump / info
    var menu by remember { mutableStateOf(false) }
    var remaining by remember { mutableStateOf(false) }

    fun showHud(h: Hud) { hud = h; hudTick++ }
    LaunchedEffect(hudTick) { if (hud != null) { delay(900); hud = null } }

    // 读取上次播放进度(只在成为当前页时读一次)
    LaunchedEffect(isCurrent, item.id) {
        if (isCurrent && resumeMs < 0) {
            // 先问服务器(换设备接着看),没有记录或连不上再用本机保存的
            resumeMs = if (!cfg.resume) 0L else {
                val remote = if (c.isLocal) null else kotlinx.coroutines.withTimeoutOrNull(2000) { c.api.getPlayback(item.id) }
                remote ?: c.playback.get(item.id, ns)
            }
        }
    }

    DisposableEffect(isCurrent, item.id, useSoftware, modeOverride, transcodeBitrate, blocked, resumeMs >= 0) {
        var p: ExoPlayer? = null
        val loudness = arrayOfNulls<android.media.audiofx.LoudnessEnhancer>(1)
        if (isCurrent && resumeMs >= 0 && !blocked) {
            val pl = createPlayer(ctx, c, c.settings.value, useSoftware, modeOverride)
            p = pl
            pl.addListener(object : Player.Listener {
                // 音量增益:挂在播放器的音频会话上
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    val mb = c.settings.value.volumeBoostMb
                    runCatching { loudness[0]?.release() }
                    loudness[0] = null
                    if (mb > 0 && audioSessionId != C.AUDIO_SESSION_ID_UNSET) runCatching {
                        loudness[0] = android.media.audiofx.LoudnessEnhancer(audioSessionId).apply { setTargetGain(mb); enabled = true }
                    }.onFailure { AppLog.w("player", "音量增益不可用: ${it.message}") }
                }
            })
            pl.playbackParameters = pl.playbackParameters.withSpeed(speed)
            pl.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            pl.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { firstFrame = true; error = null; AppLog.i("player", "首帧 ${item.name}") }
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying; c.videoPlaying = isPlaying }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    state = playbackState
                    AppLog.d("player", "状态=${when (playbackState) { 1 -> "IDLE"; 2 -> "BUFFERING"; 3 -> "READY"; 4 -> "ENDED"; else -> "?" }}")
                    if (playbackState == Player.STATE_ENDED) onEnded()
                }
                override fun onTracksChanged(t: Tracks) {
                    tracks = t
                    AppLog.i("player", "轨道 视频=${trackOpts(t, C.TRACK_TYPE_VIDEO).joinToString { it.label }} 音频=${trackOpts(t, C.TRACK_TYPE_AUDIO).joinToString { it.label }} 字幕=${trackOpts(t, C.TRACK_TYPE_TEXT).size}")
                }
                override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) {
                    if (v.height > 0) c.videoAspect = v.width * v.pixelWidthHeightRatio / v.height
                    if (c.settings.value.autoLandscape && v.width > v.height && ui.orientation == 0) { ui.orientation = 1; ui.autoRotated = true }
                }
                override fun onPlayerError(e: PlaybackException) {
                    AppLog.e("player", "播放错误 ${e.errorCodeName} item=${item.name} 软解重试=$useSoftware", e)
                    val codecProblem = e.errorCode in setOf(
                        PlaybackException.ERROR_CODE_DECODING_FAILED,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                    )
                    val formatProblem = e.errorCode in setOf(
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                    )
                    val cs = c.settings.value
                    if (codecProblem && !useSoftware && modeOverride == null && cs.autoSoftwareFallback) {
                        resumeMs = pl.currentPosition
                        useSoftware = true // 触发本 Effect 重建播放器(软解优先)
                    } else if (transcodeBitrate == null && cs.autoTranscode && !c.isLocal && (formatProblem || codecProblem)) {
                        // 手机解不了:改用服务端转码(服务端 ffmpeg / Jellyfin → H.264 / AAC 的 HLS)
                        val br = autoBitrate(item, cs)
                        AppLog.i("player", "自动改用服务端转码 ${br / 1000}kbps,分辨率不变(原因 ${e.errorCodeName})")
                        resumeMs = pl.currentPosition
                        useSoftware = false
                        transcodeBitrate = br
                        showHud(Hud(TgIcons.Speed, "手机无法直接播放,已改用服务端转码(${"%.1f".format(br / 1_000_000f)} Mbps,分辨率不变)"))
                    } else {
                        error = if (transcodeBitrate != null) {
                            if (e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) "服务端转码失败(多半是服务端的 ffmpeg 无法处理这个文件,详情见服务端日志)"
                            else "转码播放失败:${e.errorCodeName}"
                        } else friendlyPlayerError(e, item)
                    }
                }
            })
            pl.addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName0: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                    decoderName[0] = decoderName0
                    AppLog.i("player", "视频解码器 $decoderName0 (${initializationDurationMs}ms)")
                }
                override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                    dropped[0] += droppedFrames
                    AppLog.d("player", "丢帧 $droppedFrames / ${elapsedMs}ms")
                }
            })
            val br = transcodeBitrate
            if (br != null) {
                pl.setMediaItem(MediaItem.Builder().setUri(c.api.hlsUrl(item, br, sid)).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                AppLog.i("player", "服务端转码 ${br / 1000}kbps(分辨率不变)")
            } else {
                pl.setMediaItem(MediaItem.fromUri(c.api.fileUrl(item)))
            }
            if (resumeMs > 0) pl.seekTo((resumeMs - c.settings.value.resumeRewindSec * 1000L).coerceAtLeast(0L))
            pl.prepare()
            pl.playWhenReady = c.settings.value.autoplay
            player = pl
            AppLog.i("player", "播放 ${item.name} (${item.ext}, ${item.w}x${item.h}) 从 ${resumeMs}ms")
        }
        onDispose {
            runCatching { loudness[0]?.release() }
            p?.let { pl ->
                // 保存进度:播放不足 5 秒或快播完时视为没看,清除记录
                val pos = pl.currentPosition
                val dur = pl.duration
                val keep = pos > 5000 && (dur <= 0 || pos < dur - 5000)
                if (c.settings.value.resume && firstFrame) c.scope.launch {
                    val v = if (keep) pos else 0L
                    c.playback.set(item.id, v, ns)
                    if (!c.isLocal && c.session.session.value?.baseUrl == base0) runCatching { c.api.putPlayback(item.id, v) }
                }
                pl.release()
                if (transcodeBitrate != null) c.scope.launch { c.api.stopHls(item, sid) } // 通知服务端结束转码
            }
            player = null; firstFrame = false; playing = false
            if (p != null) c.videoPlaying = false
        }
    }

    // 不完整的视频:提醒一下,播放到被截断处会停止
    LaunchedEffect(isCurrent, item.id) {
        if (isCurrent && item.flags.truncated && !blocked) showHud(Hud(TgIcons.Warning, "文件不完整(下载可能中断),播放到被截断处会停止"))
    }

    // 睡眠定时 / A-B 循环 / 技术信息
    LaunchedEffect(player, ui.sleepAt) {
        val pl = player ?: return@LaunchedEffect
        val at = ui.sleepAt
        if (at > 0) {
            val wait = at - android.os.SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)
            pl.pause(); ui.sleepAt = 0
            showHud(Hud(TgIcons.Timer, "睡眠定时:已暂停"))
            AppLog.i("player", "睡眠定时到点,已暂停")
        }
    }
    LaunchedEffect(player, abA, abB) {
        val pl = player ?: return@LaunchedEffect
        val a = abA; val b = abB
        if (a == null || b == null) return@LaunchedEffect
        while (true) { if (pl.currentPosition >= b) pl.seekTo(a); delay(100) }
    }
    LaunchedEffect(player, cfg.showStats) {
        val pl = player
        if (pl == null || !cfg.showStats) { stats = ""; return@LaunchedEffect }
        while (true) {
            val v = pl.videoFormat
            val a = pl.audioFormat
            stats = buildString {
                append("解码器:").append(decoderName[0].ifEmpty { "—" }).append('\n')
                if (v != null) {
                    append("视频:").append(codecName(v.sampleMimeType) ?: "?")
                    append("  ${v.width}×${v.height}")
                    if (v.frameRate > 0) append("  ${"%.1f".format(v.frameRate)}fps")
                    if (v.bitrate > 0) append("  ${v.bitrate / 1000}kbps")
                    append('\n')
                }
                if (a != null) append("音频:").append(codecName(a.sampleMimeType) ?: "?").append("  ${a.channelCount}ch ${a.sampleRate}Hz\n")
                transcodeBitrate?.let { append("服务端转码:${it / 1000}kbps(分辨率不变)\n") }
                append("丢帧:${dropped[0]}   缓冲:${pl.totalBufferedDuration / 1000}s   速度:${pl.playbackParameters.speed}x")
            }
            delay(1000)
        }
    }

    // 增强渲染的实时信息(输出帧率 / 处理耗时 / 是否已因性能停用超分)
    LaunchedEffect(enhView, cfg.showStats, cfg.enhFrc, cfg.enhFpsOverlay) {
        while (true) {
            enhStats = if (cfg.showStats) enhView?.stats.orEmpty() else ""
            frcText = if (cfg.enhFpsOverlay && cfg.enhFrc != "off") enhView?.fpsText.orEmpty() else ""
            delay(1000)
        }
    }

    // 画中画:更新自动小窗所需信息
    LaunchedEffect(isCurrent) { if (!isCurrent) c.videoPlaying = false }

    fun enterPip() {
        val a = act ?: return
        val r = (c.videoAspect.takeIf { it > 0f } ?: (16f / 9f)).coerceIn(0.5f, 2.3f)
        runCatching {
            a.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational((r * 1000).toInt(), 1000)).build())
        }.onFailure { AppLog.w("player", "进入小窗失败", it) }
    }

    // ---- 手势用的系统量
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    fun curBrightness(): Float {
        val w = act?.window?.attributes?.screenBrightness ?: -1f
        if (w >= 0f) return w
        return runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
    }
    DisposableEffect(Unit) {
        onDispose { act?.window?.let { w -> w.attributes = w.attributes.also { it.screenBrightness = -1f } } }
    }

    val pl by rememberUpdatedState(player)
    val seekStep = cfg.seekForwardSec
    var tapAccum by remember { mutableIntStateOf(0) }
    var tapSide by remember { mutableIntStateOf(0) }
    var tapUntil by remember { mutableLongStateOf(0L) }
    var longPress by remember { mutableStateOf(false) }

    fun seekBy(sign: Int, steps: Int) {
        val p = pl ?: return
        val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (p.currentPosition + sign * steps * seekStep * 1000L).coerceIn(0, dur)
        p.seekTo(target)
        showHud(Hud(null, (if (sign > 0) "▶▶ +" else "◀◀ −") + steps * seekStep + " 秒"))
    }

    val showControls = ui.chrome && !inPip
    // ---- 退出动画:SurfaceView 不会跟着页面一起缩放 / 淡出(它在窗口后面"挖洞"显示),
    // 所以页面开始退出(返回手势一开始 / 点返回)时,把当前画面截成一张位图盖在上面,再把 SurfaceView 藏起来,
    // 缩放和淡出就作用在这张位图上,效果和别的页面一致。手势取消时恢复。
    val navScope = com.localtg.ui.LocalNavScope.current
    val exiting = navScope?.transition?.targetState == androidx.compose.animation.EnterExitState.PostExit
    var snap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var snapRect by remember { mutableStateOf(android.graphics.Rect()) }
    var surfaceView by remember { mutableStateOf<android.view.SurfaceView?>(null) }
    LaunchedEffect(exiting) {
        if (!exiting) { snap = null; return@LaunchedEffect }
        val sv = surfaceView ?: return@LaunchedEffect
        if (snap != null || sv.width <= 0 || sv.height <= 0 || !sv.holder.surface.isValid) return@LaunchedEffect
        val bmp = android.graphics.Bitmap.createBitmap(maxOf(sv.width / 2, 1), maxOf(sv.height / 2, 1), android.graphics.Bitmap.Config.ARGB_8888)
        val ok = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
            runCatching {
                android.view.PixelCopy.request(sv, bmp, { r -> if (cont.isActive) cont.resumeWith(Result.success(r == android.view.PixelCopy.SUCCESS)) }, android.os.Handler(android.os.Looper.getMainLooper()))
            }.onFailure { if (cont.isActive) cont.resumeWith(Result.success(false)) }
        }
        if (ok) {
            snapRect = android.graphics.Rect(sv.left, sv.top, sv.right, sv.bottom)
            snap = bmp
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val enh = com.localtg.render.EnhanceConfig(upscale = cfg.enhUpscale, frc = cfg.enhFrc, frcAdaptive = cfg.enhFrcAdaptive, hdr = cfg.enhHdr, hdrPeakNits = cfg.enhPeak, upscaleMaxSrcHeight = cfg.enhMaxH)
        var enhFailed by remember(item.id) { mutableStateOf(false) }
        // HDR 片源本身不处理;转码播放的是 H.264 SDR,可以处理
        val useEnh = enh.active && !enhFailed && item.video?.hdr.isNullOrEmpty() && android.os.Build.VERSION.SDK_INT >= 26
        pl?.let { p ->
          if (useEnh) {
            AndroidView(
                factory = { com.localtg.render.EnhancedVideoView(it).apply {
                    onFailure = { enhFailed = true; showHud(Hud(TgIcons.Warning, "画质增强不可用,已改回普通播放")) }
                    enhView = this
                    surfaceView = this
                } },
                update = { v ->
                    v.visibility = if (snap != null) android.view.View.INVISIBLE else android.view.View.VISIBLE
                    v.keepScreenOn = cfg.keepScreenOn
                    v.setEnhance(enh, resize)
                    v.setPlayer(p)
                },
                onRelease = { v -> v.release() },
                modifier = Modifier.fillMaxSize(),
            )
            // 字幕:PlayerView(不带画面表面)只负责画字幕
            AndroidView(
                factory = { ctx2 -> android.view.LayoutInflater.from(ctx2).inflate(com.localtg.R.layout.player_subtitles, null) as PlayerView },
                update = { it.player = p; styleSubtitles(it, cfg) },
                modifier = Modifier.fillMaxSize(),
            )
          } else {
            AndroidView(
                factory = { PlayerView(it).apply {
                    useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    surfaceView = videoSurfaceView as? android.view.SurfaceView
                } },
                update = {
                    it.videoSurfaceView?.visibility = if (snap != null) android.view.View.INVISIBLE else android.view.View.VISIBLE
                    it.player = p
                    styleSubtitles(it, cfg)
                    it.keepScreenOn = cfg.keepScreenOn
                    it.resizeMode = when (resize) {
                        "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
          }
        }
        snap?.let { b ->
            val d = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.foundation.Image(
                b.asImageBitmap(), null,
                contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
                modifier = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(snapRect.left, snapRect.top) }
                    .size(with(d) { snapRect.width().toDp() }, with(d) { snapRect.height().toDp() }),
            )
        }
        if (!firstFrame) { // 封面先显示,首帧到达后消失
            AsyncImage(
                model = c.api.posterUrl(item), contentDescription = null,
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
            )
        }

        // ---------------- 手势层
        val locked = ui.locked
        val gestures = cfg.gestures && !locked
        Box(
            Modifier.fillMaxSize()
                .pointerInput(locked, gestures, seekStep) {
                    detectTapGestures(
                        onTap = {
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (gestures && tapSide != 0 && now < tapUntil) { // VLC:双击后继续点击,累加快进快退
                                tapAccum++; tapUntil = now + 600; seekBy(tapSide, tapAccum)
                            } else ui.chrome = !ui.chrome
                        },
                        onDoubleTap = { off ->
                            if (!gestures) return@detectTapGestures
                            val w = size.width
                            val side = when { off.x < w / 3f -> -1; off.x > w * 2f / 3f -> 1; else -> 0 }
                            if (side == 0) {
                                pl?.let { if (it.isPlaying) it.pause() else it.play() }
                                showHud(Hud(if (pl?.isPlaying == true) TgIcons.Play else TgIcons.Pause, if (pl?.isPlaying == true) "播放" else "暂停"))
                            } else {
                                tapSide = side; tapAccum = 1; tapUntil = android.os.SystemClock.elapsedRealtime() + 600
                                seekBy(side, 1)
                            }
                        },
                        onLongPress = {
                            if (!gestures) return@detectTapGestures
                            pl?.let {
                                longPress = true
                                it.playbackParameters = it.playbackParameters.withSpeed(cfg.longPressSpeed)
                                showHud(Hud(TgIcons.Speed, "${cfg.longPressSpeed}x 倍速播放中"))
                                hudTick = Int.MAX_VALUE / 2 // 长按期间一直显示
                            }
                        },
                        onPress = {
                            tryAwaitRelease()
                            if (longPress) {
                                longPress = false
                                pl?.let { it.playbackParameters = it.playbackParameters.withSpeed(speed) }
                                hud = null
                            }
                        },
                    )
                }
                .pointerInput(gestures, cfg.swipeSeek, cfg.swipeSpanSec, cfg.gestureSense) {
                    if (!gestures) return@pointerInput
                    // VLC:左半屏上下 = 亮度,右半屏上下 = 音量,水平滑动 = 快进快退
                    var axis = 0 // 0 未定,1 水平,2 垂直
                    var left = true
                    var startPos = 0L
                    var dx = 0f
                    var acc = 0f
                    var startVal = 0f
                    detectDragGestures(
                        onDragStart = { off ->
                            axis = 0; dx = 0f; acc = 0f; left = off.x < size.width / 2f
                            startPos = pl?.currentPosition ?: 0L
                            startVal = if (left) curBrightness() else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        },
                        onDragEnd = {
                            if (axis == 1 && cfg.swipeSeek) pl?.let { p ->
                                val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                                p.seekTo((startPos + (dx / size.width * cfg.swipeSpanSec * 1000f).toLong()).coerceIn(0, dur))
                            }
                            hud = null; axis = 0
                        },
                        onDragCancel = { hud = null; axis = 0 },
                    ) { change, d ->
                        if (axis == 0) {
                            dx += d.x; acc += d.y
                            if (abs(dx) > 24f || abs(acc) > 24f) {
                                axis = if (abs(dx) > abs(acc)) 1 else 2
                                dx = if (axis == 1) dx else 0f; acc = if (axis == 2) acc else 0f
                            }
                        } else if (axis == 1) {
                            if (cfg.swipeSeek) {
                                dx += d.x
                                val dur = pl?.duration ?: 0L
                                val target = (startPos + (dx / size.width * cfg.swipeSpanSec * 1000f).toLong()).coerceIn(0, if (dur > 0) dur else Long.MAX_VALUE)
                                val delta = target - startPos
                                showHud(Hud(null, (if (delta >= 0) "+" else "−") + fmt(abs(delta)) + "   " + fmt(target) + " / " + fmt(dur)))
                                hudTick = Int.MAX_VALUE / 2 - 1
                            }
                        } else {
                            acc -= d.y
                            val v = (startVal + acc / size.height * cfg.gestureSense).coerceIn(0f, 1f)
                            if (left) {
                                act?.window?.let { w -> w.attributes = w.attributes.also { it.screenBrightness = v.coerceAtLeast(0.01f) } }
                                showHud(Hud(TgIcons.Brightness, "亮度 ${(v * 100).roundToInt()}%", v))
                            } else {
                                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).roundToInt(), 0)
                                showHud(Hud(TgIcons.Volume, "音量 ${(v * 100).roundToInt()}%", v))
                            }
                            hudTick = Int.MAX_VALUE / 2 - 2
                        }
                        change.consume()
                    }
                }
                .pointerInput(gestures) {
                    if (!gestures) return@pointerInput
                    // 双指捏合:放大 → 填满裁剪,缩小 → 适应屏幕(VLC 的做法)
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var zoom = 1f
                        do {
                            val ev = awaitPointerEvent()
                            if (ev.changes.size >= 2) zoom *= ev.calculateZoom()
                        } while (ev.changes.any { it.pressed })
                        if (zoom > 1.15f && resize != "zoom") { resize = "zoom"; showHud(Hud(TgIcons.AspectRatio, "画面:裁剪填满")) }
                        else if (zoom < 0.87f && resize != "fit") { resize = "fit"; showHud(Hud(TgIcons.AspectRatio, "画面:适应屏幕")) }
                    }
                },
        )

        // 服务端判定损坏(0 字节 / 无法解析):不创建播放器,直接说明原因
        if (blocked && isCurrent) {
            Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(TgIcons.Warning, null, tint = Color(0xFFFFB74D), modifier = Modifier.size(48.dp))
                Text(item.problem ?: "文件已损坏", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
                Text(
                    formatSize(item.size) + " · " + item.name, color = Color(0xB3FFFFFF), fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp), maxLines = 2,
                )
                if (item.size > 0L) TextButton(onClick = { forceTry = true }) { Text("仍然尝试播放") }
            }
        }

        // 缓冲圈
        if (state == Player.STATE_BUFFERING && firstFrame) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(44.dp), color = Color.White)
        }

        // HUD(手势提示)
        hud?.let { h ->
            Row(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp)
                    .clip(RoundedCornerShape(24.dp)).background(Color(0xB3000000)).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                h.icon?.let { Icon(it, null, tint = Color.White, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)) }
                Text(h.text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                h.progress?.let { p ->
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.width(80.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55FFFFFF))) {
                        Box(Modifier.fillMaxWidth(p).height(4.dp).background(Color.White))
                    }
                }
            }
        }

        // 播完提示
        if (state == Player.STATE_ENDED && !loop && showControls) {
            RoundBtn(TgIcons.Replay, "重播", 64.dp, Modifier.align(Alignment.Center)) { pl?.let { it.seekTo(0); it.play() } }
        }

        // ---------------- 顶栏(VLC:返回、标题、音轨 / 字幕 / 更多)
        AnimatedVisibility(
            showControls, Modifier.align(Alignment.TopStart),
            enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + slideOutVertically { -it / 2 },
        ) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CtrlIcon(TgIcons.Back, "返回") { onBack() }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(item.name, color = Color.White, fontSize = 16.sp, maxLines = 1, fontWeight = FontWeight.Medium)
                    Text(
                        listOfNotNull(item.w?.let { "${item.w}×${item.h}" }, formatSize(item.size), transcodeBitrate?.let { "转码 ${"%.1f".format(it / 1_000_000f)}M" }, decoderName[0].takeIf { it.isNotEmpty() }?.let { if (it.startsWith("c2.android") || it.startsWith("OMX.google")) "软解" else "硬解" }).joinToString(" · "),
                        color = Color(0xB3FFFFFF), fontSize = 12.sp, maxLines = 1,
                    )
                }
                if (!locked) {
                    CtrlIcon(TgIcons.AudioTrack, "音轨") { dialog = "audio" }
                    CtrlIcon(TgIcons.Subtitles, "字幕") { dialog = "text" }
                    Box {
                        CtrlIcon(TgIcons.More, "更多") { menu = true }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("画质 / 转码  " + (transcodeBitrate?.let { "${"%.1f".format(it / 1_000_000f)} Mbps" } ?: "原画")) }, onClick = { menu = false; dialog = "quality" })
                            DropdownMenuItem(text = { Text("画质增强(超分 / 补帧 / HDR)" + if (cfg.enhUpscale != "off" || cfg.enhFrc != "off" || cfg.enhHdr != "off") "  ✓" else "") }, onClick = { menu = false; dialog = "enhance" })
                            DropdownMenuItem(text = { Text("播放速度  ${"%.2f".format(speed).trimEnd('0').trimEnd('.')}x") }, onClick = { menu = false; dialog = "speed" })
                            DropdownMenuItem(text = { Text("跳转到指定时间") }, onClick = { menu = false; dialog = "jump" })
                            DropdownMenuItem(text = { Text(when { abA == null -> "A-B 循环:设置起点 A"; abB == null -> "A-B 循环:设置终点 B"; else -> "A-B 循环:取消" }) }, onClick = {
                                menu = false
                                val now = pl?.currentPosition ?: 0L
                                when { abA == null -> { abA = now; showHud(Hud(TgIcons.Repeat, "起点 A ${fmt(now)}")) }
                                    abB == null -> { if (now > abA!!) { abB = now; showHud(Hud(TgIcons.Repeat, "循环 ${fmt(abA!!)} – ${fmt(now)}")) } }
                                    else -> { abA = null; abB = null; showHud(Hud(TgIcons.Repeat, "已取消 A-B 循环")) } }
                            })
                            DropdownMenuItem(text = { Text(if (loop) "单个循环:开 ✓" else "单个循环:关") }, onClick = {
                                menu = false; loop = !loop
                                pl?.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                            })
                            DropdownMenuItem(text = { Text("睡眠定时" + if (ui.sleepAt != 0L) "(已设置)" else "") }, onClick = { menu = false; dialog = "sleep" })
                            DropdownMenuItem(text = { Text("小窗播放") }, onClick = { menu = false; enterPip() })
                            DropdownMenuItem(text = { Text("改用软件解码重试") }, onClick = { menu = false; resumeMs = pl?.currentPosition ?: 0L; modeOverride = "sw_first" })
                            DropdownMenuItem(text = { Text("改用硬件解码重试") }, onClick = { menu = false; resumeMs = pl?.currentPosition ?: 0L; modeOverride = "hw_first" })
                            if (!c.isLocal) DropdownMenuItem(text = { Text("保存到手机") }, onClick = { menu = false; scope.launch { com.localtg.data.saveToPhoneWithToast(ctx, c.http, c.api, item) } })
                            DropdownMenuItem(text = { Text("媒体信息") }, onClick = { menu = false; dialog = "info" })
                        }
                    }
                }
            }
        }

        // ---------------- 底部(VLC:进度条 + 锁 / 上一个 / 快退 / 播放 / 快进 / 下一个 / 比例)
        AnimatedVisibility(
            showControls, Modifier.align(Alignment.BottomStart),
            enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .navigationBarsPadding().padding(horizontal = 12.dp).padding(top = 24.dp, bottom = 6.dp),
            ) {
                if (!locked) {
                    pl?.let { SeekBar(it, remaining) { remaining = !remaining } }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        CtrlIcon(TgIcons.Lock, "锁定控制") { ui.locked = true; ui.chrome = false; showHud(Hud(TgIcons.Lock, "已锁定,点击屏幕解锁")) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CtrlIcon(TgIcons.SkipPrev, "上一个", enabled = onPrev != null) { onPrev?.invoke() }
                            TextCtrl("−${cfg.seekBackSec}") { pl?.let { it.seekTo((it.currentPosition - cfg.seekBackSec * 1000L).coerceAtLeast(0)) } }
                            RoundBtn(if (playing) TgIcons.Pause else TgIcons.Play, if (playing) "暂停" else "播放", 56.dp) {
                                pl?.let { if (it.playbackState == Player.STATE_ENDED) { it.seekTo(0); it.play() } else if (it.isPlaying) it.pause() else it.play() }
                            }
                            TextCtrl("+${cfg.seekForwardSec}") { pl?.let { it.seekTo(it.currentPosition + cfg.seekForwardSec * 1000L) } }
                            CtrlIcon(TgIcons.SkipNext, "下一个", enabled = onNext != null) { onNext?.invoke() }
                        }
                        Row {
                            CtrlIcon(TgIcons.Rotate, "屏幕方向") {
                                ui.orientation = (ui.orientation + 1) % 3
                                ui.autoRotated = false
                                showHud(Hud(TgIcons.Rotate, listOf("方向:自动", "方向:横屏", "方向:竖屏")[ui.orientation]))
                            }
                            CtrlIcon(TgIcons.AspectRatio, "画面比例") {
                                resize = when (resize) { "fit" -> "zoom"; "zoom" -> "fill"; else -> "fit" }
                                showHud(Hud(TgIcons.AspectRatio, "画面:" + when (resize) { "fit" -> "适应屏幕"; "zoom" -> "裁剪填满"; else -> "拉伸填满" }))
                            }
                        }
                    }
                }
            }
        }
        // 锁定后只留一个解锁按钮
        if (locked && ui.chrome && !inPip) {
            RoundBtn(TgIcons.LockOpen, "解锁", 52.dp, Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) { ui.locked = false; ui.chrome = true }
        }

        if ((stats.isNotEmpty() || enhStats.isNotEmpty()) && !inPip) {
            Text(
                listOf(stats, enhStats).filter { it.isNotEmpty() }.joinToString("\n"), color = Color(0xFFB9F6CA), fontSize = 11.sp, lineHeight = 14.sp,
                modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 8.dp, bottom = 140.dp)
                    .background(Color(0x99000000)).padding(6.dp),
            )
        }

        // 补帧时右上角的帧率小字(控制条显示时让到顶栏下面)
        if (frcText.isNotEmpty() && !inPip) {
            Text(
                frcText, color = Color(0xFFB9F6CA), fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = if (showControls) 56.dp else 4.dp, end = 10.dp)
                    .background(Color(0x66000000), androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        error?.let { msg ->
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(msg, color = Color(0xFFFF8A80), fontSize = 16.sp)
                Row {
                    TextButton(onClick = { error = null; resumeMs = 0L; modeOverride = "sw_first" }) { Text("软解重试") }
                    TextButton(onClick = { error = null; resumeMs = 0L; modeOverride = "hw_first" }) { Text("硬解重试") }
                    if (transcodeBitrate == null) TextButton(onClick = { error = null; resumeMs = 0L; transcodeBitrate = autoBitrate(item, c.settings.value) }) { Text("服务端转码") }
                    else TextButton(onClick = { error = null; resumeMs = 0L; transcodeBitrate = null }) { Text("直接播放") }
                    TextButton(onClick = {
                        val n = AppLog.copyRecent(ctx)
                        Toast.makeText(ctx, "已复制 $n 字日志", Toast.LENGTH_SHORT).show()
                    }) { Text("复制日志") }
                }
            }
        }
    }

    // ---------------- 对话框
    val p = player
    when (dialog) {
        "audio" -> {
            val opts = trackOpts(tracks, C.TRACK_TYPE_AUDIO)
            PickDialog("音轨", opts.map { it.label to it.selected }.ifEmpty { listOf("没有可选音轨" to false) }, { dialog = "" }) { i ->
                if (p != null && i in opts.indices) { selectTrack(p, opts[i]); AppLog.i("player", "选择音轨 ${opts[i].label}") }
                dialog = ""
            }
        }
        "text" -> {
            val opts = trackOpts(tracks, C.TRACK_TYPE_TEXT)
            val off = p?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) != false || opts.none { it.selected }
            val rows = listOf("关闭字幕" to off) + opts.map { it.label to (it.selected && !off) }
            PickDialog(if (opts.isEmpty()) "字幕(此视频没有内嵌字幕)" else "字幕", rows, { dialog = "" }) { i ->
                if (p != null) {
                    if (i == 0) p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    else selectTrack(p, opts[i - 1])
                }
                dialog = ""
            }
        }
        "quality" -> {
            // 转码只改码率,不改分辨率
            val rates = listOf(null, 1_500_000, 3_000_000, 6_000_000, 12_000_000, 20_000_000)
            PickDialog(
                "画质 / 转码(只调码率,分辨率不变)",
                listOf("原画(直接播放)").plus(rates.drop(1).map { "服务端转码 ${"%.1f".format(it!! / 1_000_000f)} Mbps" }).mapIndexed { i, t -> t to (rates[i] == transcodeBitrate) },
                { dialog = "" },
            ) { i ->
                dialog = ""
                if (rates[i] != transcodeBitrate) {
                    resumeMs = p?.currentPosition ?: 0L
                    useSoftware = false
                    transcodeBitrate = rates[i]
                    AppLog.i("player", "手动切换画质:" + (rates[i]?.let { "转码 ${it / 1000}kbps" } ?: "原画"))
                }
            }
        }
        "enhance" -> {
            val s = c.settings.value
            val rows = listOf(
                "关闭全部增强" to (s.enhUpscale == "off" && s.enhFrc == "off" && s.enhHdr == "off"),
                "超分:FSR(通用,很快)" to (s.enhUpscale == "fsr"),
                "超分:Anime4K 小模型(动漫)" to (s.enhUpscale == "anime4k_s"),
                "超分:Anime4K 中模型(动漫,更好)" to (s.enhUpscale == "anime4k_m"),
                "补帧:帧混合(最省电)" to (s.enhFrc == "blend"),
                "补帧:运动补偿(平衡)" to (s.enhFrc == "mc"),
                "补帧:运动补偿·轻量(省电)" to (s.enhFrc == "mc_fast"),
                "补帧:运动补偿·高质量" to (s.enhFrc == "mc_hq"),
                "SDR→HDR" to (s.enhHdr != "off"),
            )
            PickDialog("画质增强(点选后立即生效;再点一次取消)", rows, { dialog = "" }) { i ->
                dialog = ""
                c.settings.update {
                    when (i) {
                        0 -> copy(enhUpscale = "off", enhFrc = "off", enhHdr = "off")
                        1 -> copy(enhUpscale = if (enhUpscale == "fsr") "off" else "fsr")
                        2 -> copy(enhUpscale = if (enhUpscale == "anime4k_s") "off" else "anime4k_s")
                        3 -> copy(enhUpscale = if (enhUpscale == "anime4k_m") "off" else "anime4k_m")
                        4 -> copy(enhFrc = if (enhFrc == "blend") "off" else "blend")
                        5 -> copy(enhFrc = if (enhFrc == "mc") "off" else "mc")
                        6 -> copy(enhFrc = if (enhFrc == "mc_fast") "off" else "mc_fast")
                        7 -> copy(enhFrc = if (enhFrc == "mc_hq") "off" else "mc_hq")
                        else -> copy(enhHdr = if (enhHdr != "off") "off" else "auto")
                    }
                }
                showHud(Hud(TgIcons.Speed, "画质增强设置已更新"))
            }
        }
        "speed" -> SpeedDialog(speed, { dialog = "" }) { v ->
            speed = v; p?.let { it.playbackParameters = it.playbackParameters.withSpeed(v) }
            AppLog.i("player", "倍速 ${v}x")
        }
        "sleep" -> PickDialog(
            "睡眠定时",
            listOf("关闭" to (ui.sleepAt == 0L), "15 分钟" to false, "30 分钟" to false, "45 分钟" to false, "60 分钟" to false, "90 分钟" to false, "播完当前视频后停止" to (ui.sleepAt == -1L)),
            { dialog = "" },
        ) { i ->
            ui.sleepAt = when (i) {
                0 -> 0L; 6 -> -1L
                else -> android.os.SystemClock.elapsedRealtime() + listOf(0, 15, 30, 45, 60, 90)[i] * 60_000L
            }
            dialog = ""
        }
        "jump" -> JumpDialog(p?.duration ?: 0L, { dialog = "" }) { ms -> p?.seekTo(ms); dialog = "" }
        "info" -> InfoDialog(item, p, tracks, decoderName[0], { dialog = "" })
    }
}

/** 把 ExoPlayer 的错误翻译成人话:区分下载中断的不完整文件、网络问题、文件已不存在等。 */
private fun friendlyPlayerError(e: PlaybackException, item: Item): String {
    var c: Throwable? = e
    var http: Int? = null
    var eof = false
    while (c != null) {
        if (c is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) http = c.responseCode
        if (c is java.io.EOFException) eof = true
        c = c.cause
    }
    return when {
        http == 416 || eof || e.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
            "视频文件不完整(多半是下载中断留下的),无法播放到被截断处之后"
        http == 404 -> "文件已不存在,可能被移动或删除了(服务端下次扫描会更新)"
        http == 422 -> "文件大小为 0 字节(下载失败留下的空文件)"
        http == 401 || http == 403 -> "登录已失效,请重新登录"
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "连接服务器失败或超时,请检查网络和服务端是否在运行"
        e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "视频文件已损坏,无法解析"
        else -> "无法播放:${e.errorCodeName}"
    }
}

private fun selectTrack(p: ExoPlayer, o: TrackOpt) {
    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(o.group.type, false)
        .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
        .build()
}

// ---------------------------------------------------------------- 组件

@Composable
private fun CtrlIcon(icon: ImageVector, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = if (enabled) Color.White else Color(0x55FFFFFF), modifier = Modifier.size(24.dp)) }
}

@Composable
private fun TextCtrl(text: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RoundBtn(icon: ImageVector, desc: String, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(size).clip(CircleShape).background(Color(0x66000000)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = Color.White, modifier = Modifier.size(size * 0.55f)) }
}

@Composable
private fun SeekBar(player: ExoPlayer, remaining: Boolean, onToggleRemaining: () -> Unit) {
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player) {
        while (true) {
            if (!dragging) pos = player.currentPosition
            dur = player.duration.coerceAtLeast(0)
            delay(250)
        }
    }
    val shown = if (dragging) (dragFrac * dur).toLong() else pos
    val frac = if (dur > 0) (if (dragging) dragFrac else pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(fmt(shown), color = Color.White, fontSize = 13.sp, modifier = Modifier.width(52.dp))
        Slider(
            value = frac,
            onValueChange = { dragging = true; dragFrac = it },
            onValueChangeFinished = { player.seekTo((dragFrac * dur).toLong()); dragging = false },
            modifier = Modifier.weight(1f).height(32.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFF8800), activeTrackColor = Color(0xFFFF8800), inactiveTrackColor = Color(0x55FFFFFF),
            ),
        )
        Text(
            if (remaining) "-" + fmt(dur - shown) else fmt(dur), color = Color.White, fontSize = 13.sp,
            modifier = Modifier.width(60.dp).clickable(onClick = onToggleRemaining), textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
private fun PickDialog(title: String, rows: List<Pair<String, Boolean>>, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                rows.forEachIndexed { i, (label, sel) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(i) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = sel, onClick = { onPick(i) })
                        Text(label, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun SpeedDialog(current: Float, onDismiss: () -> Unit, onSet: (Float) -> Unit) {
    var v by remember { mutableFloatStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("播放速度  ${"%.2f".format(v)}x") },
        text = {
            Column {
                Slider(value = v, onValueChange = { v = (it * 20).roundToInt() / 20f; onSet(v) }, valueRange = 0.25f..4f)
                Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { s ->
                        TextButton(onClick = { v = s; onSet(s) }) { Text("${s}x") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun JumpDialog(durationMs: Long, onDismiss: () -> Unit, onJump: (Long) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳转到指定时间") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == ':' } }, singleLine = true,
                    label = { Text("格式 分:秒 或 时:分:秒,例如 12:30") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (durationMs > 0) Text("总时长 ${fmt(durationMs)}", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parts = text.split(':').mapNotNull { it.toLongOrNull() }
                val sec = when (parts.size) { 1 -> parts[0]; 2 -> parts[0] * 60 + parts[1]; 3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]; else -> -1L }
                if (sec >= 0) onJump((sec * 1000).coerceAtMost(if (durationMs > 0) durationMs else Long.MAX_VALUE))
            }) { Text("跳转") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun InfoDialog(item: Item, p: ExoPlayer?, tracks: Tracks, decoder: String, onDismiss: () -> Unit) {
    val text = buildString {
        append("文件:").append(item.name).append('\n')
        append("大小:").append(formatSize(item.size)).append("    时长:").append(fmt(p?.duration ?: 0L)).append('\n')
        append("时间:").append(formatDateTime(item.takenAt)).append('\n')
        append("视频解码器:").append(decoder.ifEmpty { "—" }).append('\n')
        p?.videoFormat?.let { v ->
            append("\n视频\n  编码 ").append(codecName(v.sampleMimeType)).append(if (v.codecs != null) " (${v.codecs})" else "").append('\n')
            append("  分辨率 ${v.width}×${v.height}")
            if (v.frameRate > 0) append("  ${"%.2f".format(v.frameRate)} fps")
            if (v.bitrate > 0) append("  ${v.bitrate / 1000} kbps")
            append('\n')
            if (v.colorInfo != null) append("  色彩 ").append(v.colorInfo.toString()).append('\n')
        }
        trackOpts(tracks, C.TRACK_TYPE_AUDIO).forEach { append("\n音轨  ").append(if (it.selected) "● " else "○ ").append(it.label) }
        val subs = trackOpts(tracks, C.TRACK_TYPE_TEXT)
        if (subs.isNotEmpty()) { append("\n"); subs.forEach { append("\n字幕  ").append(it.label) } }
        item.video?.let { vi -> append("\n\n容器 ").append(vi.container).append("    HDR ").append(vi.hdr.ifEmpty { "无" }) }
    }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("媒体信息") },
        text = { Text(text, fontSize = 13.sp, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
