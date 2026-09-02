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

    /**
     * Whether the pattern has nothing left actively growing, so [PatternEngine] can
     * safely fade out once [DrawState.segments] reaches the configured density cap.
     *
     * Defaults to true (fade the instant the cap is hit) because that is correct for a
     * pattern like [GrowingLinePattern] that always immediately starts a new line the
     * moment one finishes — there is no "settling" point short of a hard cutoff.
     * [FlowingLinePattern] overrides this: it stops *spawning* new seeds at the cap but
     * lets its already-growing ones keep going, so cutting them off mid-curve would
     * produce exactly the "stops in the middle of nowhere" look the cap is meant to
     * avoid. Reported once per frame, not consulted mid-[update].
     */
    fun isSettled(): Boolean = true
}

enum class PatternType {
    /** A single line walking the screen, turning on a fixed angle grid. */
    ANGULAR,

    /** Spokes fired one at a time from an anchor point, sweeping around it. */
    RADIAL,

    /** Several curves grow at once from the outer perimeter — canvas edges plus lines
     *  already drawn — wandering under Perlin-noise steering and stopping exactly
     *  where they cross existing geometry or the edge. */
    FLOWING;

    companion object {
        fun fromNameOrDefault(name: String?): PatternType =
            entries.firstOrNull { it.name == name } ?: ANGULAR
    }
}

fun createPatternGenerator(type: PatternType): PatternGenerator = when (type) {
    PatternType.ANGULAR -> GeometricLinePattern()
    PatternType.RADIAL -> RadialBurstPattern()
    PatternType.FLOWING -> FlowingLinePattern()
}
