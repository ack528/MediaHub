package com.localtg.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ThumbHashTest {
    // 服务端(Go)对"左半红、右半蓝"的 32×24 图编码出的 ThumbHash
    private val hash = "FfYC9ah+i5l4h3iAd4eHc4+I+IiI"

    @Test
    fun decodesServerHashToSameColors() {
        val d = ThumbHash.decode(Base64.getDecoder().decode(hash))
        assertNotNull(d)
        d!!
        assertTrue("宽高比应该接近 4:3: ${d.w}x${d.h}", d.w.toFloat() / d.h in 1.2f..1.5f)
        fun px(x: Int, y: Int) = d.argb[y * d.w + x]
        val left = px(d.w / 8, d.h / 2)
        val right = px(d.w * 7 / 8, d.h / 2)
        assertTrue("左边应该偏红: ${Integer.toHexString(left)}", ((left shr 16) and 0xFF) > 170 && (left and 0xFF) < 110)
        assertTrue("右边应该偏蓝: ${Integer.toHexString(right)}", (right and 0xFF) > 170 && ((right shr 16) and 0xFF) < 110)
        assertEquals(0xFF, (left ushr 24))
    }

    @Test
    fun rejectsBrokenInput() {
        assertNull(ThumbHash.decode(ByteArray(2)))
        assertNull(ThumbHash.decode(Base64.getDecoder().decode(hash).copyOf(8))) // 截断
    }
}
