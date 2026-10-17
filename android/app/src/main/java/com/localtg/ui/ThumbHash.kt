package com.localtg.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.Base64
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ThumbHash(https://github.com/evanw/thumbhash,MIT)的解码端:把服务端给的约 25 字节解成一张 32 像素左右的模糊小图,
 * 缩略图还没到之前先显示它(和 Telegram 一样,滑动时是色块轮廓而不是灰块)。解码约 0.1 毫秒,结果按哈希缓存。
 */
object ThumbHash {
    class Decoded(val w: Int, val h: Int, val argb: IntArray)

    /** 解码;数据不合法返回 null(调用方退回纯色占位)。 */
    fun decode(hash: ByteArray): Decoded? {
        if (hash.size < 5) return null
        fun b(i: Int) = hash[i].toInt() and 0xFF
        val header24 = b(0) or (b(1) shl 8) or (b(2) shl 16)
        val header16 = b(3) or (b(4) shl 8)
        val lDc = (header24 and 63) / 63f
        val pDc = ((header24 shr 6) and 63) / 31.5f - 1f
        val qDc = ((header24 shr 12) and 63) / 31.5f - 1f
        val lScale = ((header24 shr 18) and 31) / 31f
        val hasAlpha = (header24 shr 23) != 0
        val pScale = ((header16 shr 3) and 63) / 63f
        val qScale = ((header16 shr 9) and 63) / 63f
        val isLandscape = (header16 shr 15) != 0
        val lx = max(3, if (isLandscape) (if (hasAlpha) 5 else 7) else header16 and 7)
        val ly = max(3, if (isLandscape) header16 and 7 else (if (hasAlpha) 5 else 7))
        val aDc = if (hasAlpha) {
            if (hash.size < 6) return null
            (b(5) and 15) / 15f
        } else 1f
        val acStart = if (hasAlpha) 6 else 5
        var acIndex = 0
        fun channel(nx: Int, ny: Int, scale: Float): FloatArray? {
            val out = ArrayList<Float>()
            for (cy in 0 until ny) {
                var cx = if (cy > 0) 0 else 1
                while (cx * ny < nx * (ny - cy)) {
                    val idx = acStart + (acIndex shr 1)
                    if (idx >= hash.size) return null
                    val v = (b(idx) shr ((acIndex and 1) shl 2)) and 15
                    acIndex++
                    out.add(((v / 7.5f) - 1f) * scale)
                    cx++
                }
            }
            return out.toFloatArray()
        }
        val lAc = channel(lx, ly, lScale) ?: return null
        val pAc = channel(3, 3, pScale * 1.25f) ?: return null
        val qAc = channel(3, 3, qScale * 1.25f) ?: return null

        val ratio = lx.toFloat() / ly
        val w = (if (ratio > 1f) 32f else 32f * ratio).roundToInt().coerceAtLeast(1)
        val h = (if (ratio > 1f) 32f / ratio else 32f).roundToInt().coerceAtLeast(1)
        val out = IntArray(w * h)
        val fx = FloatArray(max(lx, 3))
        val fy = FloatArray(max(ly, 3))
        for (y in 0 until h) {
            for (cy in fy.indices) fy[cy] = cos(PI.toFloat() / h * (y + 0.5f) * cy)
            for (x in 0 until w) {
                for (cx in fx.indices) fx[cx] = cos(PI.toFloat() / w * (x + 0.5f) * cx)
                var l = lDc; var p = pDc; var q = qDc
                var j = 0
                for (cy in 0 until ly) {
                    var cx = if (cy > 0) 0 else 1
                    val fy2 = fy[cy] * 2f
                    while (cx * ly < lx * (ly - cy)) {
                        l += lAc[j] * fx[cx] * fy2
                        cx++; j++
                    }
                }
                j = 0
                for (cy in 0 until 3) {
                    var cx = if (cy > 0) 0 else 1
                    val fy2 = fy[cy] * 2f
                    while (cx < 3 - cy) {
                        val f = fx[cx] * fy2
                        p += pAc[j] * f
                        q += qAc[j] * f
                        cx++; j++
                    }
                }
                val bb = l - 2f / 3f * p
                val rr = (3f * l - bb + q) / 2f
                val gg = rr - q
                fun c(v: Float) = max(0, min(255, (255f * v).roundToInt()))
                out[y * w + x] = (0xFF shl 24) or (c(rr) shl 16) or (c(gg) shl 8) or c(bb)
            }
        }
        return Decoded(w, h, out)
    }

    private val cache by lazy { android.util.LruCache<String, ImageBitmap>(600) }

    /** 服务端返回的是标准 Base64;解不出来 / 为空返回 null。结果按字符串缓存(滑动时同一格反复出现)。 */
    fun bitmapFor(b64: String?): ImageBitmap? {
        if (b64.isNullOrEmpty()) return null
        cache.get(b64)?.let { return it }
        val d = runCatching { decode(Base64.getDecoder().decode(b64)) }.getOrNull() ?: return null
        val bmp = Bitmap.createBitmap(d.argb, d.w, d.h, Bitmap.Config.ARGB_8888).asImageBitmap()
        cache.put(b64, bmp)
        return bmp
    }
}

/** 缩略图加载前的占位:有 ThumbHash 就是模糊的色块轮廓,没有就是原来的纯色。 */
@androidx.compose.runtime.Composable
fun thumbHashPainter(item: com.localtg.data.Item): androidx.compose.ui.graphics.painter.Painter {
    val th = item.thumbhash
    return androidx.compose.runtime.remember(th) {
        ThumbHash.bitmapFor(th)?.let { androidx.compose.ui.graphics.painter.BitmapPainter(it, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low) }
            ?: androidx.compose.ui.graphics.painter.ColorPainter(androidx.compose.ui.graphics.Color(0x22888888))
    }
}
