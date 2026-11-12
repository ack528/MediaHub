package com.localtg.ui

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.tanh

/**
 * 自动音量均衡:不同视频的响度不一样(有的录得很小声,有的很吵),播放时在应用内部给声音乘一个系数 ×g,
 * 把响度拉到差不多的水平。**不改系统音量**(音量条、音量键、手势调的音量都不受影响),只是解码后的声音数据乘一个系数。
 *
 * 做法(插在 ExoPlayer 的音频输出链里,对解码后的 PCM 处理):
 *  - 测响度:每 0.4 秒一块,混成单声道、先过一个约 100Hz 的高通(人耳对低频不敏感,近似 K 计权),算均方根 dBFS;
 *    太安静的块(< -55dBFS,静音 / 片头黑场)和比当前响度低 20dB 以上的块不计,免得把安静段拉得很响。
 *  - 定系数:目标响度 -20dBFS,系数 = 目标 - 实测,限制在 ±12dB(×0.25 ~ ×4)。
 *  - 平滑:开头 2 秒快速收敛;之后变大慢(每秒 1.5dB)、变小快(每秒 6dB),不会忽大忽小,也不会突然很响。
 *  - 防爆音:乘完系数后超过 -1dBFS 的部分软限幅(tanh 压缩),放大后也不会削波。
 *  - 每个视频收敛后的系数记下来([LoudnessMemory]),下次打开同一个视频直接从这个系数开始,不用再收敛一次。
 * 只处理 16 位整数和浮点 PCM;其它格式(直通给功放的杜比 / DTS 等)原样通过。
 */
@UnstableApi
class LoudnessNormalizer(startGainDb: Float?) : BaseAudioProcessor() {
    /** 关掉时系数平滑地回到 ×1。 */
    @Volatile var enabled = true
    /** 当前实际使用的系数(dB);界面上显示 ×10^(dB/20)。 */
    @Volatile var gainDb = startGainDb ?: 0f; private set
    /** 已经测到足够多的有声块,系数可以记下来了。 */
    val converged get() = blocks >= 8

    private var desiredDb = gainDb
    private var loudness = if (startGainDb != null) TARGET_DB - startGainDb else Float.NaN
    private var blocks = if (startGainDb != null) 8 else 0
    private var startedFrames = 0L

    private var channels = 0
    private var sampleRate = 0
    private var float = false
    private var hpA = 0f
    private var hpX = 0f; private var hpY = 0f
    private var sumSq = 0.0; private var count = 0; private var blockFrames = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET // 不认识的格式:不处理(原样通过)
        }
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val rc = 1.0 / (2 * Math.PI * 100.0)
        hpA = (rc / (rc + 1.0 / sampleRate)).toFloat()
        blockFrames = (sampleRate * 0.4).toInt().coerceAtLeast(1)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerSample = if (float) 4 else 2
        val frameBytes = bytesPerSample * channels
        val frames = inputBuffer.remaining() / frameBytes
        if (frames == 0) return
        val out = replaceOutputBuffer(frames * frameBytes)
        val inp = inputBuffer.order(ByteOrder.nativeOrder())

        // 系数从 g0 线性过渡到 g1(这一段的末尾),避免一段内突变出现咔嗒声
        val g0 = dbToLin(gainDb)
        updateGain(frames)
        val g1 = dbToLin(gainDb)
        val step = (g1 - g0) / frames
        var g = g0
        val start = inp.position()
        for (f in 0 until frames) {
            var mono = 0f
            for (ch in 0 until channels) {
                val idx = start + (f * channels + ch) * bytesPerSample
                val x = if (float) inp.getFloat(idx) else inp.getShort(idx) / 32768f
                mono += x
                val y = limit(x * g)
                if (float) out.putFloat(y) else out.putShort((y * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }
            measure(mono / channels)
            g += step
        }
        inp.position(start + frames * frameBytes)
        out.flip()
        startedFrames += frames
    }

    /** 高通后的均方根,0.4 秒一块,更新响度估计。 */
    private fun measure(x: Float) {
        val y = hpA * (hpY + x - hpX)
        hpX = x; hpY = y
        sumSq += (y * y).toDouble(); count++
        if (count < blockFrames) return
        val db = (10 * log10(sumSq / count + 1e-12)).toFloat()
        sumSq = 0.0; count = 0
        if (db < GATE_DB) return
        if (!loudness.isNaN() && db < loudness - 20f) return
        blocks++
        loudness = when {
            loudness.isNaN() -> db
            blocks <= 5 -> loudness + (db - loudness) / blocks // 开头几块:直接取平均,很快就有可用的估计
            else -> loudness + (db - loudness) * 0.04f         // 之后:约 10 秒的时间常数,不随一时的大声 / 小声来回变
        }
        desiredDb = (TARGET_DB - loudness).coerceIn(-MAX_DB, MAX_DB)
    }

    private fun updateGain(frames: Int) {
        val target = if (enabled && !loudness.isNaN()) desiredDb else if (enabled) gainDb else 0f
        val secs = frames.toFloat() / sampleRate.coerceAtLeast(1)
        val early = startedFrames < sampleRate * 2L
        val up = if (early) 8f else 1.5f      // dB/秒
        val down = if (early) 8f else 6f
        val d = target - gainDb
        gainDb += if (d > 0) minOf(d, up * secs) else maxOf(d, -down * secs)
    }

    override fun onFlush() {
        // 拖动进度条:滤波器状态清零,响度估计和系数保留(还是同一个视频)
        hpX = 0f; hpY = 0f; sumSq = 0.0; count = 0
    }

    override fun onReset() { onFlush() }

    companion object {
        const val TARGET_DB = -20f
        const val MAX_DB = 12f
        private const val GATE_DB = -55f
        private const val KNEE = 0.89f // -1 dBFS

        fun dbToLin(db: Float): Float = 10f.pow(db / 20f)

        /** 软限幅:-1dBFS 以下原样,以上用 tanh 平滑压到 1.0 以内。 */
        private fun limit(x: Float): Float {
            val a = abs(x)
            if (a <= KNEE) return x
            return sign(x) * (KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE)))
        }

    }
}

/** 每个视频收敛后的音量系数(dB),下次打开同一个视频直接用。最多记 500 个,存在应用私有的 SharedPreferences 里。 */
object LoudnessMemory {
    private const val MAX = 500
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("loudness", Context.MODE_PRIVATE)

    fun get(ctx: Context, itemId: String): Float? = prefs(ctx).takeIf { it.contains(itemId) }?.getFloat(itemId, 0f)

    fun put(ctx: Context, itemId: String, gainDb: Float) {
        val p = prefs(ctx)
        val e = p.edit().putFloat(itemId, gainDb)
        // 超过上限清掉一半(SharedPreferences 不记写入顺序,删的是任意一半;被删的视频下次再收敛一次即可)
        if (p.all.size > MAX) p.all.keys.take(MAX / 2).forEach { if (it != itemId) e.remove(it) }
        e.apply()
    }
}
