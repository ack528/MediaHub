package com.localtg.data

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class SlideshowAppearanceTest {
    @Test fun oldSettingsKeepDefaultStyleAndCustomStyleSurvivesSerialization() {
        val old=AppJson.decodeFromString<AppSettings>("{\"slideshowSeconds\":5}")
        assertEquals(4f,old.slideshowBarThickness)
        assertEquals("#CCCCCC",old.slideshowBarColor)
        val custom=old.copy(slideshowBarThickness=0.5f,slideshowBarColor="#12ABEF")
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
}
