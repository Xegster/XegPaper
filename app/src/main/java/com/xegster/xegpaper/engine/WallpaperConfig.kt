package com.xegster.xegpaper.engine

/**
 * User-tunable wallpaper settings. Persisted by
 * `com.xegster.xegpaper.settings.SettingsRepository` and read live by both the
 * wallpaper service and the in-app preview.
 */
data class WallpaperConfig(
    val patternType: PatternType = PatternType.ANGULAR,
    val backgroundColor: Int = DEFAULT_BACKGROUND_COLOR,
    val lineColor: Int = DEFAULT_LINE_COLOR,
    /** Drawing speed in dp per second. */
    val speedDpPerSecond: Float = 170f,
    /** Stroke width in dp. */
    val lineWidthDp: Float = 2.5f,
    /** How many segments accumulate before the canvas fades out and restarts. */
    val maxSegments: Int = 260,
) {
    companion object {
        const val DEFAULT_BACKGROUND_COLOR: Int = 0xFF0B0F1A.toInt()
        const val DEFAULT_LINE_COLOR: Int = 0xFF4CC9F0.toInt()

        const val MIN_SPEED_DP_PER_SECOND = 30f
        const val MAX_SPEED_DP_PER_SECOND = 600f

        const val MIN_LINE_WIDTH_DP = 0.5f
        const val MAX_LINE_WIDTH_DP = 10f

        const val MIN_SEGMENTS = 40
        const val MAX_SEGMENTS = 600

        val DEFAULT = WallpaperConfig()
    }
}
