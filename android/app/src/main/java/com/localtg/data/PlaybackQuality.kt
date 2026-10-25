package com.localtg.data

/** A manual choice is independent of source bitrate; only the server's actual limit may clamp it. */
fun rememberedVideoBitrate(settings: AppSettings, serverMax: Int, local: Boolean, broken: Boolean): Int? {
    if (!settings.rememberPlayback || settings.playerQuality <= 0 || local || broken) return null
    return minOf(settings.playerQuality, QualityTiers.cap(serverMax))
}

fun hasRememberedQuality(settings: AppSettings): Boolean = settings.rememberPlayback && settings.playerQuality >= 0

fun allowAutoQuality(settings: AppSettings, manuallySelected: Boolean): Boolean =
    settings.autoQuality && !manuallySelected && !hasRememberedQuality(settings)

/** Changing codec must not turn a temporary automatic tier into the saved quality. */
fun qualityForCodecChange(settings: AppSettings, actualBitrate: Int?): Int? =
    if (hasRememberedQuality(settings)) settings.playerQuality.takeIf { it > 0 } else actualBitrate
