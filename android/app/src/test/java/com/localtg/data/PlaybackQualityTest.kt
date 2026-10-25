package com.localtg.data

import org.junit.Assert.*
import org.junit.Test

class PlaybackQualityTest {
    @Test fun explicitTierIsKeptUntilARealServerLimitApplies() {
        val selected=AppSettings(playerQuality=4_000_000,rememberPlayback=true,autoQuality=true)
        assertEquals(4_000_000,rememberedVideoBitrate(selected,6_000_000,false,false))
        assertEquals(3_000_000,rememberedVideoBitrate(selected,3_000_000,false,false))
        assertNull(rememberedVideoBitrate(selected.copy(playerQuality=0),6_000_000,false,false))
        assertNull(rememberedVideoBitrate(selected.copy(rememberPlayback=false),6_000_000,false,false))
        assertNull(rememberedVideoBitrate(selected,6_000_000,true,false))
        assertNull(rememberedVideoBitrate(selected,6_000_000,false,true))
    }
    @Test fun automaticAdaptationRespectsManualAndRememberedChoices() {
        val automatic=AppSettings(autoQuality=true,playerQuality=-1)
        assertTrue(allowAutoQuality(automatic,false))
        assertFalse(allowAutoQuality(automatic,true))
        assertFalse(allowAutoQuality(automatic.copy(playerQuality=4_000_000),false))
        assertFalse(allowAutoQuality(automatic.copy(playerQuality=0),false))
        assertTrue(allowAutoQuality(automatic.copy(playerQuality=4_000_000,rememberPlayback=false),false))
        assertFalse(allowAutoQuality(automatic.copy(autoQuality=false),false))
    }
    @Test fun codecChangesCannotPersistATemporaryAutomaticTier() {
        val preferred=AppSettings(playerQuality=4_000_000)
        assertEquals(4_000_000,qualityForCodecChange(preferred,800_000))
        assertNull(qualityForCodecChange(preferred.copy(playerQuality=0),800_000))
        assertEquals(800_000,qualityForCodecChange(preferred.copy(playerQuality=-1),800_000))
    }
}
