package com.xegster.xegpaper.engine

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.random.Random

/**
 * One [LineGrower] curve biased toward round bubbles (see [LineGrower.Style.BLOOM],
 * the same "curvy bubbles" style [BloomPattern] uses) that, every so often as it
 * grows, detours through a sharp [ScytheGeometry] blade instead of its usual
 * bubble/blade loop, then picks the bubble growth back up from wherever the blade
 * ended. The scythe is not a side branch: it is part of the *same* path, so the line
 * reads as one continuous stroke that happens to sharpen into a blade for a stretch.
 *
 * This works by handing control back and forth between two things stepping the same
 * head: normally [grower] itself advances, but once a detour starts, [scytheTracer]
 * takes over instead — walking the scythe's precomputed outline, which starts exactly
 * on the grower's current tip (see [ScytheGeometry.tipForOuterBase], the same
 * shared-vertex trick [MultiScythePattern] uses to join scythes to each other). When
 * the tracer finishes, [LineGrower.warpTo] moves the grower's own head to the blade's
 * far end and growth resumes from there.
 */
class ScytheBubblePattern(private val random: Random = Random.Default) : PatternGenerator {

    override val type: PatternType = PatternType.SCYTHE_BUBBLE

    private val perimeter = PerimeterTracker(random)
    private var grower: LineGrower? = null
    private var scytheTracer: PointPathTracer? = null
    private var scytheExitX = 0f
    private var scytheExitY = 0f
    private var scytheExitHeading = 0f
    private var scytheLengthDp = 0f
    private var distanceSinceScytheDp = 0f

    override fun reset(state: DrawState, config: WallpaperConfig) {
        state.clear()
        grower = null
        scytheTracer = null
        distanceSinceScytheDp = 0f
    }

    /** Settled once the curve — bubbling or mid-detour — has finished. See the
     *  interface doc for why this matters. */
    override fun isSettled(): Boolean = grower == null && scytheTracer == null

    override fun update(state: DrawState, config: WallpaperConfig, deltaSeconds: Float) {
        if (!state.hasSurface || deltaSeconds <= 0f) return

        if (grower == null && scytheTracer == null) {
            if (state.segments.size >= config.maxSegments) return
            spawnGrower(state, config)
        }

        val budget = config.speedDpPerSecond * state.density * deltaSeconds

        val tracer = scytheTracer
        if (tracer != null) {
            tracer.advance(state, config.lineColor, budget)
            if (!tracer.active) finishScythe(state)
            return
        }

        val line = grower ?: return
        val consumedPx = line.step(state, budget)
        distanceSinceScytheDp += consumedPx / state.density
        if (!line.active) {
            grower = null
            return
        }
        maybeStartScythe(state, line)
    }

    private fun spawnGrower(state: DrawState, config: WallpaperConfig) {
        val spawn = perimeter.randomSpawnPoint(state)
        grower = LineGrower(
            random = random,
            startX = spawn[0],
            startY = spawn[1],
            baseAngle = spawn[2],
            maxLengthDp = randomMaxLengthDp(state),
            color = config.lineColor,
            style = LineGrower.Style.BLOOM,
        )
    }

    /** Once the curve has bubbled far enough since its last detour (or since it was
     *  seeded), occasionally starts tracing a scythe from its current tip instead of
     *  continuing to bubble — see the class doc for how control hands back afterward. */
    private fun maybeStartScythe(state: DrawState, line: LineGrower) {
        if (distanceSinceScytheDp < MIN_SCYTHE_SPACING_DP) return
        if (random.nextFloat() >= SCYTHE_PROBABILITY_PER_FRAME) return

        val sideSign = if (random.nextBoolean()) 1f else -1f
        val headingJitterDeg = range(-TIP_JITTER_DEG, TIP_JITTER_DEG)
        val tipOutHeading = line.tipHeading + Math.toRadians(headingJitterDeg.toDouble()).toFloat()
        val intoShapeHeading = tipOutHeading + PI_F

        val chordPx = range(SCYTHE_CHORD_MIN_DP, SCYTHE_CHORD_MAX_DP) * state.density
        val outerSweepDeg = range(OUTER_MIN_DEG, OUTER_MAX_DEG)
        val innerSweepDeg = outerSweepDeg - range(MIN_HORN_ANGLE_DEG, minOf(34f, outerSweepDeg - 8f))
        val outerHalfSweep = Math.toRadians(outerSweepDeg.toDouble()).toFloat()
        val innerHalfSweep = Math.toRadians(innerSweepDeg.toDouble()).toFloat()

        val tip = ScytheGeometry.tipForOuterBase(line.tipX, line.tipY, intoShapeHeading, chordPx, sideSign, outerHalfSweep)
        val outline = ScytheGeometry.buildOutline(
            tipX = tip[0],
            tipY = tip[1],
            tipHeading = tipOutHeading,
            chordPx = chordPx,
            sideSign = sideSign,
            outerHalfSweep = outerHalfSweep,
            innerHalfSweep = innerHalfSweep,
            stepPx = TRACE_CHORD_DP * state.density,
        )

        val pointCount = outline.points.size / 2
        val prevX = outline.points[(pointCount - 2) * 2]
        val prevY = outline.points[(pointCount - 2) * 2 + 1]
        scytheExitX = outline.baseBX
        scytheExitY = outline.baseBY
        scytheExitHeading = atan2(scytheExitY - prevY, scytheExitX - prevX)
        scytheLengthDp = pathLengthPx(outline.points) / state.density

        scytheTracer = PointPathTracer(outline.points)
    }

    /** Hands the head back to [grower] at the blade's far end once the detour has
     *  finished drawing — but only if it finished *by running out of path*, not by
     *  crossing something or leaving the canvas partway through, in which case the
     *  curve as a whole has ended right there. */
    private fun finishScythe(state: DrawState) {
        scytheTracer = null
        val line = grower ?: return
        val lastSegment = state.segments.lastOrNull()
        val reachedExit = lastSegment != null &&
            hypot(lastSegment.endX - scytheExitX, lastSegment.endY - scytheExitY) < PointPathTracer.DEGENERATE_PX
        if (reachedExit) {
            line.warpTo(scytheExitX, scytheExitY, scytheExitHeading, scytheLengthDp)
        } else {
            grower = null
        }
        distanceSinceScytheDp = 0f
    }

    private fun pathLengthPx(points: FloatArray): Float {
        var length = 0f
        for (i in 0 until points.size / 2 - 1) {
            length += hypot(points[i * 2 + 2] - points[i * 2], points[i * 2 + 3] - points[i * 2 + 1])
        }
        return length
    }

    private fun randomMaxLengthDp(state: DrawState): Float {
        val diagonalDp = hypot(state.width, state.height) / state.density
        val fraction = MIN_LENGTH_FRACTION + random.nextFloat() * (MAX_LENGTH_FRACTION - MIN_LENGTH_FRACTION)
        return diagonalDp * fraction
    }

    private fun range(from: Float, to: Float): Float = from + random.nextFloat() * (to - from)

    private companion object {
        const val MIN_LENGTH_FRACTION = 0.3f
        const val MAX_LENGTH_FRACTION = 0.7f

        /** The curve must bubble at least this far since its last detour before it can
         *  start another. */
        const val MIN_SCYTHE_SPACING_DP = 60f

        /** Rolled once per frame once [MIN_SCYTHE_SPACING_DP] has been covered — see
         *  [BloomPattern]'s fork roll for why this is per-frame rather than per-chord. */
        const val SCYTHE_PROBABILITY_PER_FRAME = 0.05f

        /** How far a detour's heading can stray from the curve's own current heading,
         *  in degrees — kept well short of perpendicular so the blade reads as a
         *  sharpened continuation of the stroke, not a sideways spike. */
        const val TIP_JITTER_DEG = 45f

        const val SCYTHE_CHORD_MIN_DP = 40f
        const val SCYTHE_CHORD_MAX_DP = 100f

        const val OUTER_MIN_DEG = 35f
        const val OUTER_MAX_DEG = 68f

        /** Least angle between the outer and inner arc where they meet at the tip. */
        const val MIN_HORN_ANGLE_DEG = 18f

        const val TRACE_CHORD_DP = 5f

        val PI_F = PI.toFloat()
    }
}
