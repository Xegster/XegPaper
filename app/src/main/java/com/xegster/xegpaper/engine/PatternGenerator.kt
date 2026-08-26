package com.xegster.xegpaper.engine

/**
 * The geometric rule that decides where lines start, which way they run, and when
 * they turn. Implementations only ever append to [DrawState.segments]; painting is
 * [WallpaperRenderer]'s job and surface/lifecycle handling is [PatternEngine]'s, so
 * new patterns can be added without touching either.
 */
interface PatternGenerator {

    val type: PatternType

    /** Throws away all progress and prepares for a fresh composition. */
    fun reset(state: DrawState, config: WallpaperConfig)

    /** Advances the drawing by [deltaSeconds] worth of movement. */
    fun update(state: DrawState, config: WallpaperConfig, deltaSeconds: Float)
}

enum class PatternType {
    /** A single line walking the screen, turning on a fixed angle grid. */
    ANGULAR,

    /** Spokes fired one at a time from an anchor point, sweeping around it. */
    RADIAL;

    companion object {
        fun fromNameOrDefault(name: String?): PatternType =
            entries.firstOrNull { it.name == name } ?: ANGULAR
    }
}

fun createPatternGenerator(type: PatternType): PatternGenerator = when (type) {
    PatternType.ANGULAR -> GeometricLinePattern()
    PatternType.RADIAL -> RadialBurstPattern()
}
