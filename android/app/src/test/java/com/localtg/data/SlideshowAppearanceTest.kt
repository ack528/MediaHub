package com.localtg.data

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class SlideshowAppearanceTest {
    @Test fun oldSettingsKeepDefaultStyleAndCustomStyleSurvivesSerialization() {
        val old=AppJson.decodeFromString<AppSettings>("{\"slideshowSeconds\":5}")
        assertEquals(4f,old.slideshowBarThickness)
        assertEquals("#CCCCCC",old.slideshowBarColor)
        assertEquals("top",old.slideshowBarPosition)
        assertEquals("bar",old.slideshowPhotoIndicator)
        val custom=old.copy(slideshowBarThickness=0.5f,slideshowBarColor="#12ABEF",slideshowBarPosition="right",slideshowPhotoIndicator="countdown")
        val restored=AppJson.decodeFromString<AppSettings>(AppJson.encodeToString(custom))
        assertEquals(custom,restored)
    }
    @Test fun customRgbIsNormalizedAndInvalidInputDoesNotBreakRendering() {
        assertEquals("#12ABEF",parseSlideBarColor(" 12abef "))
        assertEquals(0xFF12ABEFL,slideBarArgb("#12abef"))
        for(value in listOf("#GG0011","#FFF","#00112233","","1234567")) {
            assertNull(parseSlideBarColor(value))
            assertEquals(0xFFCCCCCCL,slideBarArgb(value))
        }
        assertEquals(0.5f,slideBarThickness(-2f))
        assertEquals(12f,slideBarThickness(100f))
        assertEquals(4f,slideBarThickness(Float.NaN))
    }
    @Test fun eachEdgeKeepsItsAnchorAndShrinksAlongTheCorrectAxis() {
        val expected=mapOf(
            "top" to SlideBarBounds(0f,0f,25f,2f),
            "bottom" to SlideBarBounds(0f,78f,25f,2f),
            "left" to SlideBarBounds(0f,0f,2f,20f),
            "right" to SlideBarBounds(98f,0f,2f,20f),
        )
        for((edge,bounds) in expected) {
            assertEquals(bounds,slideBarBounds(100f,80f,2f,edge,0.25f))
            val full=slideBarBounds(100f,80f,2f,edge,1f)
            val empty=slideBarBounds(100f,80f,2f,edge,0f)
            assertEquals(full.x,empty.x);assertEquals(full.y,empty.y)
            if(edge in listOf("top","bottom")) assertEquals(0f,empty.width) else assertEquals(0f,empty.height)
        }
        assertEquals(expected["top"],slideBarBounds(100f,80f,2f,"unknown",0.25f))
        assertEquals("bar",slidePhotoIndicator("unknown"))
        assertEquals("countdown",slidePhotoIndicator("countdown"))
        assertEquals("none",slidePhotoIndicator("none"))
    }
    @Test fun countdownSupportsSubsecondIntervalsAndDoesNotRoundUpFloatNoise() {
        assertEquals("0.5",slideCountdownText(0.5f,1f))
        assertEquals("1.0",slideCountdownText(2.5f,0.4f))
        assertEquals("1.8",slideCountdownText(5f,0.36f))
        assertEquals("0.1",slideCountdownText(0.5f,0.01f))
        assertEquals("0.0",slideCountdownText(5f,0f))
        assertEquals("0.0",slideCountdownText(5f,-1f))
        assertEquals("0.0",slideCountdownText(5f,Float.NaN))
    }
}
