package com.xegster.xegpaper.engine

import kotlin.math.hypot
import kotlin.random.Random

/**
 * [FlowingLinePattern]'s bubble-and-blade growth crossed with [BranchingLinePattern]'s
 * forking: each growing curve is a [LineGrower] biased heavily toward bubbles (see
 * [LineGrower.Style.BLOOM]) that, every so often as it travels, spawns a sibling
 * [LineGrower] from its current tip instead of only ever running to a single end. The
 * result reads as a vine blooming with round bumps that keeps dividing as it spreads.
 *
 * Forking happens entirely from the outside: nothing in [LineGrower] knows about
 * siblings. This pattern just reads a grower's current tip position and heading (see
 * [LineGrower.tipX]/[LineGrower.tipY]/[LineGrower.tipHeading]) and occasionally starts
 * a brand new [LineGrower] there, the same way [PerimeterTracker] seeds one from the
 * canvas edge — so every fork is clipped by the same [SegmentStepper] crossing logic
 * as everything else, including its own siblings and parent.
 */
class BloomPattern(private val random: Random = Random.Default) : PatternGenerator {

    override val type: PatternType = PatternType.BLOOM

    private val perimeter = PerimeterTracker(random)
    private val growers = ArrayList<Grower>()

    override fun reset(state: DrawState, config: WallpaperConfig) {
        state.clear()
        growers.clear()
    }

    /** Once every grower — original seed and every fork — has finished on its own
     *  there is nothing left to wait for — see the interface doc for why this matters. */
    override fun isSettled(): Boolean = growers.isEmpty()

    override fun update(state: DrawState, config: WallpaperConfig, deltaSeconds: Float) {
        if (!state.hasSurface || deltaSeconds <= 0f) return

        ensureSeeds(state, config)
        if (growers.isEmpty()) return

        val budget = config.speedDpPerSecond * state.density * deltaSeconds
        val perGrower = budget / growers.size
        for (index in growers.indices.reversed()) {
            val grower = growers[index]
            val consumedPx = grower.line.step(state, perGrower)
            grower.distanceSinceForkDp += consumedPx / state.density

            if (!grower.line.active) {
                growers.removeAt(index)
                continue
            }
            maybeFork(state, config, grower)
        }
    }

    private fun ensureSeeds(state: DrawState, config: WallpaperConfig) {
        while (growers.size < ACTIVE_SEED_COUNT && state.segments.size < config.maxSegments) {
            val spawn = perimeter.randomSpawnPoint(state)
            growers.add(Grower(newLineGrower(state, config, spawn[0], spawn[1], spawn[2])))
        }
    }

    /** Occasionally starts a new sibling growing from [grower]'s current tip, once it
     *  has travelled far enough since its last fork (or since it was seeded). */
    private fun maybeFork(state: DrawState, config: WallpaperConfig, grower: Grower) {
        if (growers.size >= MAX_GROWERS) return
        if (grower.distanceSinceForkDp < MIN_FORK_SPACING_DP) return
        if (random.nextFloat() >= FORK_PROBABILITY_PER_FRAME) return

        val side = if (random.nextBoolean()) 1f else -1f
        val angleOffset = side * Math.toRadians(
            (FORK_ANGLE_MIN_DEG + random.nextFloat() * (FORK_ANGLE_MAX_DEG - FORK_ANGLE_MIN_DEG)).toDouble(),
        ).toFloat()
        val line = grower.line
        growers.add(Grower(newLineGrower(state, config, line.tipX, line.tipY, line.tipHeading + angleOffset)))
        grower.distanceSinceForkDp = 0f
    }

    private fun newLineGrower(state: DrawState, config: WallpaperConfig, x: Float, y: Float, angle: Float) =
        LineGrower(
            random = random,
            startX = x,
            startY = y,
            baseAngle = angle,
            maxLengthDp = randomMaxLengthDp(state),
            color = config.lineColor,
            style = LineGrower.Style.BLOOM,
        )

    private fun randomMaxLengthDp(state: DrawState): Float {
        val diagonalDp = hypot(state.width, state.height) / state.density
        val fraction = MIN_LENGTH_FRACTION + random.nextFloat() * (MAX_LENGTH_FRACTION - MIN_LENGTH_FRACTION)
        return diagonalDp * fraction
    }

    private class Grower(val line: LineGrower, var distanceSinceForkDp: Float = 0f)

    private companion object {
        /** Seeds growing in parallel from the perimeter before any forking; also the
         *  density multiplier from the design doc. */
        const val ACTIVE_SEED_COUNT = 1

        /** Caps concurrent growers so a long-lived, heavily-forked bloom cannot cost
         *  unbounded work per frame. */
        const val MAX_GROWERS = 14

        const val MIN_LENGTH_FRACTION = 0.3f
        const val MAX_LENGTH_FRACTION = 0.7f

        /** A grower must travel at least this far since its last fork before it can
         *  fork again. */
        const val MIN_FORK_SPACING_DP = 45f

        /** Rolled once per grower per frame once [MIN_FORK_SPACING_DP] has been
         *  covered — unlike [BranchingLinePattern]'s per-chord roll, since a
         *  [LineGrower] doesn't expose its chord boundaries to the outside. */
        const val FORK_PROBABILITY_PER_FRAME = 0.05f

        const val FORK_ANGLE_MIN_DEG = 20f
        const val FORK_ANGLE_MAX_DEG = 55f
    }
}
