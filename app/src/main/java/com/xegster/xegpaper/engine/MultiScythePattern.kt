package com.xegster.xegpaper.engine

import kotlin.math.PI
import kotlin.math.min
import kotlin.random.Random

/**
 * Chains of [ScytheGeometry] scythes joined at their non-tip endpoints: one scythe's
 * base sits exactly on the next one's, the same shared-vertex construction a full
 * crescent uses for its two horns (see [ScytheGeometry.tipForOuterBase]), so the chain
 * reads as one continuous zigzag of blades rather than separate shapes bridged by
 * gaps. Sides alternate link to link, and the chain's heading wanders by a small
 * random amount each link so it curves instead of running dead straight.
 *
 * Growth otherwise works like [ScytheLinePattern]: the whole chain is built up front,
 * several candidate placements are scored against what is already drawn, and the best
 * one is traced by [PointPathTracer], clipped the instant it meets existing geometry
 * or the canvas edge — including its own earlier links, exactly like any other
 * crossing.
 */
class MultiScythePattern(private val random: Random = Random.Default) : PatternGenerator {

    override val type: PatternType = PatternType.MULTI_SCYTHE

    private val perimeter = PerimeterTracker(random)
    private var tracer: PointPathTracer? = null

    override fun reset(state: DrawState, config: WallpaperConfig) {
        state.clear()
        tracer = null
    }

    /** Settled once the chain being drawn has finished — see the interface doc for why
     *  this matters. */
    override fun isSettled(): Boolean = tracer == null

    override fun update(state: DrawState, config: WallpaperConfig, deltaSeconds: Float) {
        if (!state.hasSurface || deltaSeconds <= 0f) return

        if (tracer == null) {
            if (state.segments.size >= config.maxSegments) return
            tracer = spawnTracer(state)
        }
        val active = tracer ?: return

        val budget = config.speedDpPerSecond * state.density * deltaSeconds
        active.advance(state, config.lineColor, budget)
        if (!active.active) tracer = null
    }

    /** Scores several random chains against what is already drawn and keeps the one
     *  whose path runs longest before hitting something or the canvas edge. */
    private fun spawnTracer(state: DrawState): PointPathTracer {
        var bestChain = buildChain(state)
        var bestScore = PointPathTracer.clearFraction(state, bestChain)
        var attempt = 1
        while (bestScore < 1f && attempt < PLACEMENT_ATTEMPTS) {
            val candidate = buildChain(state)
            val score = PointPathTracer.clearFraction(state, candidate)
            if (score > bestScore) {
                bestScore = score
                bestChain = candidate
            }
            attempt++
        }
        return PointPathTracer(bestChain)
    }

    /** Builds one candidate chain as a single continuous path, spawned from the outer
     *  perimeter like every other pattern here. */
    private fun buildChain(state: DrawState): FloatArray {
        val spawn = perimeter.randomSpawnPoint(state)
        var cursorX = spawn[0]
        var cursorY = spawn[1]
        var tipOutHeading = spawn[2]
        var sideSign = if (random.nextBoolean()) 1f else -1f

        val minDp = min(state.width, state.height) / state.density
        val stepPx = TRACE_CHORD_DP * state.density
        val linkCount = MIN_LINKS + random.nextInt(MAX_LINKS - MIN_LINKS + 1)

        val chain = ArrayList<Float>(linkCount * 24)
        chain.add(cursorX)
        chain.add(cursorY)

        repeat(linkCount) { linkIndex ->
            val chordDp = (minDp * sizeFraction()).coerceIn(CHORD_MIN_DP, CHORD_MAX_DP)
            val chordPx = chordDp * state.density
            val outerSweepDeg = range(OUTER_MIN_DEG, OUTER_MAX_DEG)
            val innerSweepDeg = outerSweepDeg - range(MIN_HORN_ANGLE_DEG, min(34f, outerSweepDeg - 8f))
            val outerHalfSweep = Math.toRadians(outerSweepDeg.toDouble()).toFloat()
            val innerHalfSweep = Math.toRadians(innerSweepDeg.toDouble()).toFloat()

            // The second link in every chain gets a much sharper turn than the gentle
            // wander the rest of the chain uses, so the cluster reads as more than a
            // single blade repeated at a slightly different angle.
            val jitterRangeDeg = if (linkIndex == 1) SECOND_LINK_JITTER_DEG else JITTER_DEG
            val jitter = Math.toRadians(range(-jitterRangeDeg, jitterRangeDeg).toDouble()).toFloat()
            tipOutHeading += jitter
            val intoShapeHeading = tipOutHeading + PI_F

            val tip = ScytheGeometry.tipForOuterBase(cursorX, cursorY, intoShapeHeading, chordPx, sideSign, outerHalfSweep)
            val outline = ScytheGeometry.buildOutline(
                tipX = tip[0],
                tipY = tip[1],
                tipHeading = tipOutHeading,
                chordPx = chordPx,
                sideSign = sideSign,
                outerHalfSweep = outerHalfSweep,
                innerHalfSweep = innerHalfSweep,
                stepPx = stepPx,
            )

            // The outline's first point is this link's base, already the chain's
            // current last point — appending it again would draw a zero-length chord.
            for (i in 1 until outline.points.size / 2) {
                chain.add(outline.points[i * 2])
                chain.add(outline.points[i * 2 + 1])
            }

            cursorX = outline.baseBX
            cursorY = outline.baseBY

            // Normally alternate sides so the chain reads as a zigzag; sometimes stay
            // on the same side instead — a mirror image of the expected next link —
            // which bends the chain hard rather than snaking back and forth.
            if (random.nextFloat() >= MIRROR_PROBABILITY) sideSign = -sideSign
        }

        return chain.toFloatArray()
    }

    private fun range(from: Float, to: Float): Float = from + random.nextFloat() * (to - from)

    /** A link's chord, as a fraction of the shorter canvas side. The product of two
     *  independent draws skews toward the low end (small blades are the common case,
     *  a big one is a rare highlight) — a single uniform draw over a wide range still
     *  clusters near the middle too often to read as "different sizes" within one
     *  chain. */
    private fun sizeFraction(): Float {
        val eased = random.nextFloat() * random.nextFloat()
        return CHORD_MIN_FRACTION + eased * (CHORD_MAX_FRACTION - CHORD_MIN_FRACTION)
    }

    private companion object {
        /** Length of each straight chord approximating an arc, in dp. */
        const val TRACE_CHORD_DP = 5f

        /** How many random chains are scored before the best is drawn. */
        const val PLACEMENT_ATTEMPTS = 8

        const val MIN_LINKS = 3
        const val MAX_LINKS = 7

        /** Chord between each link's own horn and its cut base, as a fraction of the
         *  shorter canvas side — see [sizeFraction] for how this range is sampled. */
        const val CHORD_MIN_FRACTION = 0.05f
        const val CHORD_MAX_FRACTION = 0.42f
        const val CHORD_MIN_DP = 16f
        const val CHORD_MAX_DP = 190f

        const val OUTER_MIN_DEG = 40f
        const val OUTER_MAX_DEG = 90f

        /** Least angle between the outer and inner arc where they meet at the tip. */
        const val MIN_HORN_ANGLE_DEG = 20f

        /** How much the chain's heading wanders between links, in degrees. */
        const val JITTER_DEG = 22f

        /** Heading wander for the chain's second link specifically — enough to clearly
         *  point away from where the first link's tip suggested the chain was headed. */
        const val SECOND_LINK_JITTER_DEG = 95f

        /** Chance a link stays on the same side as the one before it instead of
         *  alternating. */
        const val MIRROR_PROBABILITY = 0.35f

        val PI_F = PI.toFloat()
    }
}
