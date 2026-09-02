package com.xegster.xegpaper.engine

import kotlin.math.hypot
import kotlin.random.Random

/**
 * Seed-growth pattern (see `DESIGN_SEED_GROWTH_FLOWING_PATTERN.md`): several curves
 * grow at once, each seeded from a random point on the outer perimeter — the canvas
 * edges plus every line already drawn — swelling into round bubbles and pinching into
 * pointed blades (see [LineGrower]) until it crosses existing geometry or the canvas
 * edge.
 *
 * A line becomes part of the perimeter for later seeds the instant its segments land
 * in [DrawState.segments], which is what keeps growth radiating outward from what
 * already exists — [PerimeterTracker] needs no separate bookkeeping for that.
 *
 * [ACTIVE_SEED_COUNT] curves grow in parallel, each independently budgeted at the
 * configured speed, so it also acts as the density multiplier the design calls for:
 * more concurrent seeds fill the canvas faster.
 */
class FlowingLinePattern(private val random: Random = Random.Default) : PatternGenerator {

    override val type: PatternType = PatternType.FLOWING

    private val perimeter = PerimeterTracker(random)
    private val growers = ArrayList<LineGrower>()

    override fun reset(state: DrawState, config: WallpaperConfig) {
        state.clear()
        growers.clear()
    }

    override fun update(state: DrawState, config: WallpaperConfig, deltaSeconds: Float) {
        if (!state.hasSurface || deltaSeconds <= 0f) return

        ensureSeeds(state, config)
        if (growers.isEmpty()) return

        val budget = config.speedDpPerSecond * state.density * deltaSeconds
        for (index in growers.indices.reversed()) {
            growers[index].step(state, budget)
            if (!growers[index].active) growers.removeAt(index)
        }
    }

    private var debugColorIndex = 0
    private val debugPalette = intArrayOf(
        0xFFFF1744.toInt(), 0xFF2979FF.toInt(), 0xFF00E676.toInt(), 0xFFFFEA00.toInt(),
        0xFFD500F9.toInt(), 0xFFFF6D00.toInt(), 0xFF00E5FF.toInt(), 0xFFFFFFFF.toInt(),
    )

    private fun ensureSeeds(state: DrawState, config: WallpaperConfig) {
        while (growers.size < ACTIVE_SEED_COUNT && state.segments.size < config.maxSegments) {
            val spawn = perimeter.randomSpawnPoint(state)
            growers.add(
                LineGrower(
                    random = random,
                    startX = spawn[0],
                    startY = spawn[1],
                    baseAngle = spawn[2],
                    maxLengthDp = randomMaxLengthDp(state),
                    color = debugPalette[debugColorIndex++ % debugPalette.size],
                ),
            )
        }
    }

    private fun randomMaxLengthDp(state: DrawState): Float {
        val diagonalDp = hypot(state.width, state.height) / state.density
        val fraction = MIN_LENGTH_FRACTION + random.nextFloat() * (MAX_LENGTH_FRACTION - MIN_LENGTH_FRACTION)
        return diagonalDp * fraction
    }

    private companion object {
        /** Curves growing in parallel; also the density multiplier from the design doc. */
        const val ACTIVE_SEED_COUNT = 1

        const val MIN_LENGTH_FRACTION = 0.3f
        const val MAX_LENGTH_FRACTION = 0.7f
    }
}
