package com.xegster.xegpaper.engine

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * Draws crescents, half-moons, claws and scythe blades by tracing their exact
 * geometry instead of steering a wandering line.
 *
 * Every shape is two arcs that share two sharp points (the "horns"), the way a
 * crescent moon is an outer circle with an inner terminator cut out of it:
 *
 *  - the **outer arc** is a circular arc through both horns; at a half-sweep of 90
 *    degrees it is the full moon-outline semicircle `x^2 + y^2 = r^2`, and a larger
 *    sweep wraps past a semicircle into a hooked claw;
 *  - the **inner arc** is a shallower arc through the same two horns. Its sweep is
 *    what sets the crescent's thickness, and at zero it degenerates into the straight
 *    terminator line `x = 0` — a half-disc, the quarter moon.
 *
 * The outline is traced as one closed path — out along the outer arc, back along the
 * inner one — so the finished shape has a sharp point at each horn. Both arcs use
 * circular sweeps that differ by at least [MIN_HORN_ANGLE_DEG], so they leave each horn
 * at a real angle rather than tangentially; a tangent cusp would put the two polylines
 * within a pixel of each other and the crossing test could not tell them apart.
 *
 * Growth otherwise works like [FlowingLinePattern]: each shape starts on the outer
 * perimeter (see [PerimeterTracker]) and is clipped by [SegmentStepper] the instant it
 * meets existing geometry or the canvas edge. Because a crescent's whole outline is
 * known up front, several candidate placements are scored first and the one with the
 * most clear run is drawn, which keeps most shapes whole instead of truncated.
 */
class ScytheLinePattern(private val random: Random = Random.Default) : PatternGenerator {

    override val type: PatternType = PatternType.SCYTHE

    private val perimeter = PerimeterTracker(random)
    private var tracer: PointPathTracer? = null

    override fun reset(state: DrawState, config: WallpaperConfig) {
        state.clear()
        tracer = null
    }

    /** Settled once the shape being drawn has finished — see the interface doc for why
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

    /** Scores several random placements against what is already drawn and keeps the one
     *  whose outline runs longest before hitting something or the canvas edge. */
    private fun spawnTracer(state: DrawState): PointPathTracer {
        var bestOutline = randomOutline(state)
        var bestScore = PointPathTracer.clearFraction(state, bestOutline, skipLastChord = true)
        var attempt = 1
        while (bestScore < 1f && attempt < PLACEMENT_ATTEMPTS) {
            val candidate = randomOutline(state)
            val score = PointPathTracer.clearFraction(state, candidate, skipLastChord = true)
            if (score > bestScore) {
                bestScore = score
                bestOutline = candidate
            }
            attempt++
        }
        return PointPathTracer(bestOutline)
    }

    private fun randomOutline(state: DrawState): FloatArray {
        val spawn = perimeter.randomSpawnPoint(state)
        val minDp = min(state.width, state.height) / state.density
        val chordDp = (minDp * range(CHORD_MIN_FRACTION, CHORD_MAX_FRACTION)).coerceIn(CHORD_MIN_DP, CHORD_MAX_DP)

        val shape = random.nextFloat()
        val outerSweepDeg: Float
        val innerSweepDeg: Float
        val lean: Float
        when {
            shape < CRESCENT_SHARE -> {
                outerSweepDeg = range(72f, 92f)
                innerSweepDeg = outerSweepDeg - range(26f, 42f)
                lean = range(-0.12f, 0.12f)
            }
            shape < CRESCENT_SHARE + CLAW_SHARE -> {
                outerSweepDeg = range(100f, 125f)
                innerSweepDeg = outerSweepDeg - range(22f, 34f)
                lean = range(-0.2f, 0.2f)
            }
            shape < CRESCENT_SHARE + CLAW_SHARE + BLADE_SHARE -> {
                outerSweepDeg = range(38f, 62f)
                innerSweepDeg = outerSweepDeg - range(MIN_HORN_ANGLE_DEG, min(34f, outerSweepDeg - 8f))
                lean = range(-MAX_LEAN, MAX_LEAN)
            }
            else -> {
                // Half-disc: a semicircle closed by the straight terminator line.
                outerSweepDeg = range(84f, 92f)
                innerSweepDeg = 0f
                lean = 0f
            }
        }

        return buildOutline(
            startX = spawn[0],
            startY = spawn[1],
            heading = spawn[2],
            chordPx = chordDp * state.density,
            sideSign = if (random.nextBoolean()) 1f else -1f,
            outerHalfSweep = Math.toRadians(outerSweepDeg.toDouble()).toFloat(),
            innerHalfSweep = Math.toRadians(innerSweepDeg.toDouble()).toFloat(),
            lean = lean,
            stepPx = TRACE_CHORD_DP * state.density,
        )
    }

    /**
     * The closed outline as interleaved x/y pixels, starting and ending exactly at
     * ([startX], [startY]) — the first horn.
     *
     * In a local frame the chord between the horns runs along `d` and the shape bulges
     * toward `n` (`d` turned a quarter-turn toward [sideSign]). An arc of half-sweep
     * `a` through both horns has sagitta `s = (chord / 2) * tan(a / 2)` and radius
     * `rho = (chord^2 / 4 + s^2) / (2 * s)`; sweeping its angle `phi` from `-a` to `+a`
     * gives `along = rho * sin(phi)` and `bulge = rho * cos(phi) - (rho - s)`. The arc
     * leaves the first horn tilted `a` toward `n`, so `d` is `heading - sideSign * a`
     * for the outer arc — that is what makes the shape open away from whatever it
     * spawned from.
     *
     * [lean] shears both arcs identically along the chord (by `lean * chord * sin(pi * f)`),
     * leaving the horns where they are while dragging the bulge toward one end — the
     * hooked, off-centre profile of a scythe blade rather than a symmetric moon. Past
     * roughly 0.3 the two arcs can cross each other, hence [MAX_LEAN].
     */
    private fun buildOutline(
        startX: Float,
        startY: Float,
        heading: Float,
        chordPx: Float,
        sideSign: Float,
        outerHalfSweep: Float,
        innerHalfSweep: Float,
        lean: Float,
        stepPx: Float,
    ): FloatArray {
        val chordAngle = heading - sideSign * outerHalfSweep
        val dx = cos(chordAngle)
        val dy = sin(chordAngle)
        val nx = -sideSign * dy
        val ny = sideSign * dx
        val midX = startX + dx * chordPx / 2f
        val midY = startY + dy * chordPx / 2f

        fun arcLength(halfSweep: Float): Float =
            if (halfSweep < STRAIGHT_EPSILON) chordPx else chordPx * halfSweep / sin(halfSweep)

        val outerCount = maxOf(MIN_ARC_POINTS, ceil(arcLength(outerHalfSweep) / stepPx).toInt())
        val innerCount = maxOf(MIN_ARC_POINTS, ceil(arcLength(innerHalfSweep) / stepPx).toInt())
        val points = FloatArray((outerCount + 1 + innerCount) * 2)

        fun place(index: Int, f: Float, halfSweep: Float) {
            val along: Float
            val bulge: Float
            if (halfSweep < STRAIGHT_EPSILON) {
                along = -chordPx / 2f + chordPx * f
                bulge = 0f
            } else {
                val sagitta = (chordPx / 2f) * tan(halfSweep / 2f)
                val rho = (chordPx * chordPx / 4f + sagitta * sagitta) / (2f * sagitta)
                val phi = -halfSweep + 2f * halfSweep * f
                along = rho * sin(phi)
                bulge = rho * cos(phi) - (rho - sagitta)
            }
            val shearedAlong = along + lean * chordPx * sin(PI.toFloat() * f)
            points[index * 2] = midX + dx * shearedAlong + nx * bulge
            points[index * 2 + 1] = midY + dy * shearedAlong + ny * bulge
        }

        for (i in 0..outerCount) place(i, i.toFloat() / outerCount, outerHalfSweep)
        for (i in 1..innerCount) place(outerCount + i, 1f - i.toFloat() / innerCount, innerHalfSweep)

        // Both ends are the spawn point itself, exactly, so the closed path meets the
        // vertex it started from instead of stopping a rounding error short of it.
        points[0] = startX
        points[1] = startY
        points[points.size - 2] = startX
        points[points.size - 1] = startY
        return points
    }

    private fun range(from: Float, to: Float): Float = from + random.nextFloat() * (to - from)

    private companion object {
        /** Length of each straight chord approximating an arc, in dp. */
        const val TRACE_CHORD_DP = 5f

        const val MIN_ARC_POINTS = 10

        /** How many random placements are scored before the best is drawn. */
        const val PLACEMENT_ATTEMPTS = 10

        /** Shape mix: symmetric crescents, hooked claws, shallow blades, half-discs. */
        const val CRESCENT_SHARE = 0.40f
        const val CLAW_SHARE = 0.22f
        const val BLADE_SHARE = 0.26f

        /** Chord between the horns, as a fraction of the shorter canvas side. */
        const val CHORD_MIN_FRACTION = 0.30f
        const val CHORD_MAX_FRACTION = 0.60f
        const val CHORD_MIN_DP = 60f
        const val CHORD_MAX_DP = 230f

        /** Least angle between the outer and inner arc where they meet at a horn. */
        const val MIN_HORN_ANGLE_DEG = 20f

        /** Largest chord shear before the two arcs can cross — see [buildOutline]. */
        const val MAX_LEAN = 0.28f

        /** Half-sweeps below this many radians are treated as a straight line. */
        const val STRAIGHT_EPSILON = 1e-3f
    }
}
