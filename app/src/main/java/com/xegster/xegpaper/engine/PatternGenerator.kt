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
    FLOWING,

    /** One line grows from the perimeter and occasionally forks into two independent
     *  tips, each carrying its own fixed curvature. See [BranchingLinePattern]. */
    BRANCHING,

    /** Diffusion-limited aggregation: undirected random walkers stick the instant they
     *  touch existing geometry. See [AggregationLinePattern]. */
    AGGREGATION,

    /** Straight cell walls grown along Voronoi-style perpendicular bisectors between
     *  best-candidate-sampled sites. See [CellularPattern]. */
    CELLULAR,

    /** [FlowingLinePattern]'s bubble-and-blade growth crossed with [BranchingLinePattern]'s
     *  forking, biased heavily toward bubbles. See [BloomPattern]. */
    BLOOM,

    /** Crescents, half-moons, claws and scythe blades, each traced exactly as two arcs
     *  meeting at sharp horns, seeded from the perimeter like [FLOWING]. See
     *  [ScytheLinePattern]. */
    SCYTHE,

    /** A bubble-heavy [FLOWING]-style curve (see [BLOOM]) that occasionally detours
     *  through a scythe blade and keeps growing from where it ended. See
     *  [ScytheBubblePattern]. */
    SCYTHE_BUBBLE,

    /** Scythes chained base-to-base into a zigzag of blades. See
     *  [MultiScythePattern]. */
    MULTI_SCYTHE;

    companion object {
        fun fromNameOrDefault(name: String?): PatternType =
            entries.firstOrNull { it.name == name } ?: ANGULAR
    }
}

fun createPatternGenerator(type: PatternType): PatternGenerator = when (type) {
    PatternType.ANGULAR -> GeometricLinePattern()
    PatternType.RADIAL -> RadialBurstPattern()
    PatternType.FLOWING -> FlowingLinePattern()
    PatternType.BRANCHING -> BranchingLinePattern()
    PatternType.AGGREGATION -> AggregationLinePattern()
    PatternType.CELLULAR -> CellularPattern()
    PatternType.BLOOM -> BloomPattern()
    PatternType.SCYTHE -> ScytheLinePattern()
    PatternType.SCYTHE_BUBBLE -> ScytheBubblePattern()
    PatternType.MULTI_SCYTHE -> MultiScythePattern()
}
