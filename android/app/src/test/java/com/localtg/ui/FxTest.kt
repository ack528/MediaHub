package com.localtg.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FxTest {
    private val pos = mapOf(0 to (0f to 60f), 1 to (60f to 100f), 2 to (160f to 80f))

    @Test
    fun indicatorSitsOnTabWhenSettled() {
        assertEquals(60f to 100f, tabIndicator(1f, pos, 3))
        assertEquals(0f to 60f, tabIndicator(0f, pos, 3))
    }

    @Test
    fun indicatorFollowsTheFingerBetweenTabs() {
        // 页面拖了一半:位置和宽度都在两个标签之间的正中
        assertEquals(30f to 80f, tabIndicator(0.5f, pos, 3))
        assertEquals(110f to 90f, tabIndicator(1.5f, pos, 3))
    }

    @Test
    fun indicatorClampsAtTheEnds() {
        assertEquals(160f to 80f, tabIndicator(2.4f, pos, 3)) // 最后一页往外拖(过度滚动)
        assertEquals(0f to 60f, tabIndicator(-0.3f, pos, 3))
    }

    @Test
    fun indicatorWaitsForMeasure() {
        assertNull(tabIndicator(0.5f, emptyMap(), 3))
        assertEquals(0f to 60f, tabIndicator(0.5f, mapOf(0 to (0f to 60f)), 3)) // 下一个标签还没量出来,先停在当前
    }
}
