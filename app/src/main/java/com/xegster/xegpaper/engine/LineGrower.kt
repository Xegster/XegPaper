package com.xegster.xegpaper.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Grows one curved line from a spawn point as a chain of "bubbles" (round bumps, turned
 * at constant curvature) and "blades" (a hook whose curvature decays to zero, so it
 * curls and then straightens into a point), joined by short straight "necks" — see
 * `DESIGN_SEED_GROWTH_FLOWING_PATTERN.md` and the reference sketches in `Resources/`. A
 * light Perlin wobble rides on top purely as surface texture; it never drives the
 * overall shape, which is why the bubbles and blades read clearly instead of dissolving
 * into a generic wander.
 *
 * A bubble's total turn (see [BUBBLE_TURN_MAX_DEG]) is most of a full loop, so it reads
 * as a round bump rather than a shallow curve — capping the angle is *not* what keeps
 * it from crossing itself, though: two consecutive loops curling in opposite directions
 * can cross each other even when neither individually turns anywhere near a full
 * circle. So every chord — whatever produced its heading, including the exit-steering
 * blend near max length, which otherwise could point straight back through the line's
 * own tangle — passes through [safeHeading], which nudges it away from anything within
 * [SELF_CLEARANCE_DP] of an *earlier* stretch of this same line. That is what actually
 * guarantees the line can only ever stop by touching *other* geometry or the canvas
 * edge, never itself; the plain crossing check in [advance] (which does not exclude
 * this line's own segments) is only the last-resort backstop for the
 * practically-impossible case of a heading blocked in every direction.
 *
 * The line is drawn as many short straight chords with a slightly different heading
 * each time; consecutive chords share an exact vertex rather than a tolerance match,
 * which is what lets [IntersectionDetector] tell them apart from a genuine crossing
 * (see the note on this in `GrowingLinePattern`).
 *
 * The line always stops exactly where it crosses existing geometry or leaves the
 * canvas. Its random max length is only a soft cap: once [APPROACH_FRACTION] of it is
 * spent, heading blends toward the nearest other line or the canvas edge (see
 * [nearestExitPoint]) so growth reliably ends by touching something instead of trailing
 * off in open space.
 */
class LineGrower(
    random: Random,
    startX: Float,
    startY: Float,
    private val baseAngle: Float,
    private val maxLengthDp: Float,
    private val color: Int,
) {
    private val random = random
    private val noise = PerlinNoise(random)

    /** This line's own segments in growth order. Used two ways: [nearestExitPoint]
     *  excludes all of them (a point on the line's own tail would otherwise trivially
     *  "win" at distance zero), while [wouldApproachOwnPath] checks the *earlier* ones
     *  (everything but the trailing [SELF_CHECK_EXCLUDE_RECENT] segments, which are
     *  naturally close to the head by construction) to keep the line from curling back
     *  into itself. Crossing any segment, including this line's own, still stops it
     *  outright (see [advance]) as a last-resort safety net. */
    private val ownSegments = ArrayList<LineSegment>()

    private var headX = startX
    private var headY = startY
    private var distanceTraveledDp = 0f
    private var chordRemainingPx = 0f
    private var chordAngle = baseAngle

    // Loop/neck state machine driving the bubble-or-blade shape (see class doc).
    private var currentHeading = baseAngle
    private var phase = Phase.NECK
    private var loopDirection = if (random.nextBoolean()) 1f else -1f
    private var isBubble = true
    private var loopCurvaturePerDp = 0f
    private var loopTurnRemainingRadians = 0f
    private var bladeLengthDp = 0f
    private var bladeTraveledDp = 0f
    private var neckRemainingDp = INITIAL_RUNUP_DP

    var active = true
        private set

    /** Advances up to [budgetPx] worth of drawing, stopping early if the line
     *  terminates. Returns the px actually consumed. */
    fun step(state: DrawState, budgetPx: Float): Float {
        var remaining = budgetPx
        var consumedTotal = 0f
        while (remaining > 0f && active) {
            if (chordRemainingPx <= 0f) beginChord(state)
            val subStep = min(remaining, chordRemainingPx)
            if (subStep <= 0f) break
            val consumed = advance(state, subStep)
            remaining -= consumed
            chordRemainingPx -= consumed
            consumedTotal += consumed
        }
        return consumedTotal
    }

    private fun beginChord(state: DrawState) {
        advancePhase(state)
        chordAngle = safeHeading(state, headingFor(state))
        chordRemainingPx = CHORD_DP * state.density
    }

    /** Advances the bubble/blade/neck state machine by one chord's worth of distance,
     *  updating [currentHeading] to match. */
    private fun advancePhase(state: DrawState) {
        when (phase) {
            Phase.NECK -> {
                neckRemainingDp -= CHORD_DP
                if (neckRemainingDp <= 0f) startNewLoop()
            }
            Phase.LOOP -> {
                val turnThisChord = if (isBubble) {
                    loopCurvaturePerDp * CHORD_DP
                } else {
                    // Blade: curvature ramps down to 0 over bladeLengthDp, so the hook
                    // straightens into a point instead of closing into a round bubble.
                    val t = (bladeTraveledDp / bladeLengthDp).coerceAtMost(1f)
                    loopCurvaturePerDp * (1f - t) * CHORD_DP
                }
                val candidateHeading = currentHeading + turnThisChord * loopDirection
                if (wouldApproachOwnPath(state, candidateHeading)) {
                    // Continuing to curl would bring this chord too close to an earlier
                    // stretch of the same line — cut the loop short instead of risking
                    // a self-crossing.
                    startNeck()
                    return
                }
                currentHeading = candidateHeading
                if (isBubble) {
                    loopTurnRemainingRadians -= turnThisChord
                    if (loopTurnRemainingRadians <= 0f) startNeck()
                } else {
                    bladeTraveledDp += CHORD_DP
                    if (bladeTraveledDp >= bladeLengthDp) startNeck()
                }
            }
        }
    }

    /** Whether the next chord, drawn at [candidateHeading], would come within
     *  [SELF_CLEARANCE_DP] of a stretch of this same line more than
     *  [SELF_CHECK_EXCLUDE_RECENT] segments back. The trailing segments are excluded
     *  because a curling loop is naturally within a radius's distance of its own last
     *  few chords — that is not a risk, only an approach to genuinely *earlier* geometry
     *  is. Checked at the chord's midpoint as well as its end, so a short chord cannot
     *  graze past an old segment that neither endpoint alone gets close enough to. */
    private fun wouldApproachOwnPath(state: DrawState, candidateHeading: Float): Boolean {
        val safeCount = ownSegments.size - SELF_CHECK_EXCLUDE_RECENT
        if (safeCount <= 0) return false
        val chordPx = CHORD_DP * state.density
        val lookaheadX = headX + cos(candidateHeading) * chordPx
        val lookaheadY = headY + sin(candidateHeading) * chordPx
        val midX = (headX + lookaheadX) / 2f
        val midY = (headY + lookaheadY) / 2f
        val clearancePx = SELF_CLEARANCE_DP * state.density
        for (index in 0 until safeCount) {
            val segment = ownSegments[index]
            if (IntersectionDetector.distanceToSegment(segment, lookaheadX, lookaheadY) < clearancePx ||
                IntersectionDetector.distanceToSegment(segment, midX, midY) < clearancePx
            ) {
                return true
            }
        }
        return false
    }

    /** Last-resort gate applied to every chord regardless of what produced it (loop
     *  curvature, a straight neck, or the exit-steering blend near max length): if
     *  [desired] would approach this line's own earlier path, rotate it in increasing
     *  10-degree steps, alternating sides, until a clear direction is found. This is
     *  what keeps *exit-steering* honest too — without it, heading straight for the
     *  nearest edge or line could cut straight back through a tangle of this line's own
     *  earlier loops. If literally every direction is blocked (the head is boxed in by
     *  its own path on all sides — vanishingly rare), [desired] is returned as-is and
     *  the hard crossing check in [advance] is the final backstop. */
    private fun safeHeading(state: DrawState, desired: Float): Float {
        if (!wouldApproachOwnPath(state, desired)) return desired
        for (step in 1..18) {
            val delta = Math.toRadians((step * 10).toDouble()).toFloat()
            val plus = desired + delta
            if (!wouldApproachOwnPath(state, plus)) return plus
            val minus = desired - delta
            if (!wouldApproachOwnPath(state, minus)) return minus
        }
        return desired
    }

    private fun startNewLoop() {
        isBubble = random.nextFloat() < BUBBLE_PROBABILITY
        val radiusDp = LOOP_RADIUS_MIN_DP + random.nextFloat() * (LOOP_RADIUS_MAX_DP - LOOP_RADIUS_MIN_DP)
        loopCurvaturePerDp = 1f / radiusDp
        if (isBubble) {
            val turnDegrees = BUBBLE_TURN_MIN_DEG + random.nextFloat() * (BUBBLE_TURN_MAX_DEG - BUBBLE_TURN_MIN_DEG)
            loopTurnRemainingRadians = Math.toRadians(turnDegrees.toDouble()).toFloat()
        } else {
            bladeLengthDp = BLADE_LENGTH_MIN_DP + random.nextFloat() * (BLADE_LENGTH_MAX_DP - BLADE_LENGTH_MIN_DP)
            bladeTraveledDp = 0f
        }
        if (random.nextFloat() < LOOP_FLIP_PROBABILITY) loopDirection = -loopDirection
        phase = Phase.LOOP
    }

    private fun startNeck() {
        neckRemainingDp = NECK_MIN_DP + random.nextFloat() * (NECK_MAX_DP - NECK_MIN_DP)
        phase = Phase.NECK
    }

    /** Current loop/neck heading, perturbed by a light Perlin wobble for surface
     *  texture; blended toward [nearestExitPoint] once the line nears its max length. */
    private fun headingFor(state: DrawState): Float {
        val wander = currentHeading +
            noise.sample(distanceTraveledDp * NOISE_FREQUENCY) * NOISE_AMPLITUDE_RADIANS

        val progress = distanceTraveledDp / maxLengthDp
        if (progress < APPROACH_FRACTION) return wander

        val exit = nearestExitPoint(state)
        val exitAngle = atan2(exit[1] - headY, exit[0] - headX)
        val steer = ((progress - APPROACH_FRACTION) / (1f - APPROACH_FRACTION)).coerceIn(0f, 1f)
        return lerpAngle(wander, exitAngle, steer)
    }

    /** Nearest point on another line, or the canvas edge, excluding this line's own
     *  path (which would otherwise trivially "win" at distance zero). The edge
     *  candidates guarantee a finite result even on an empty canvas. */
    private fun nearestExitPoint(state: DrawState): FloatArray {
        var bestDistance = Float.MAX_VALUE
        var bestX = headX
        var bestY = headY
        for (segment in state.segments) {
            if (segment in ownSegments) continue
            val point = IntersectionDetector.closestPointOnSegment(segment, headX, headY)
            val distance = hypot(point[0] - headX, point[1] - headY)
            if (distance < bestDistance) {
                bestDistance = distance
                bestX = point[0]
                bestY = point[1]
            }
        }
        val edgeCandidates = arrayOf(
            floatArrayOf(headX, 0f), floatArrayOf(headX, state.height),
            floatArrayOf(0f, headY), floatArrayOf(state.width, headY),
        )
        for (candidate in edgeCandidates) {
            val distance = hypot(candidate[0] - headX, candidate[1] - headY)
            if (distance < bestDistance) {
                bestDistance = distance
                bestX = candidate[0]
                bestY = candidate[1]
            }
        }
        return floatArrayOf(bestX, bestY)
    }

    /** Extends the head by up to [step] px along [chordAngle], stopping exactly at a
     *  crossing or the canvas edge if either falls within this step. Returns the px
     *  actually consumed. */
    private fun advance(state: DrawState, step: Float): Float {
        val nextX = headX + cos(chordAngle) * step
        val nextY = headY + sin(chordAngle) * step

        val crossT = IntersectionDetector.firstCrossFraction(
            state.segments, headX, headY, nextX, nextY, MIN_CROSS_FRACTION,
        )
        val edgeT = boundsFraction(state, nextX, nextY)

        val fraction: Float
        val terminates: Boolean
        when {
            crossT != null && (edgeT == null || crossT <= edgeT) -> {
                fraction = crossT
                terminates = true
            }
            edgeT != null -> {
                fraction = edgeT
                terminates = true
            }
            else -> {
                fraction = 1f
                terminates = false
            }
        }

        if (terminates && crossT != null && (edgeT == null || crossT <= edgeT)) {
            val isSelf = ownSegments.any { seg ->
                IntersectionDetector.crossFraction(
                    headX, headY, nextX, nextY, seg.startX, seg.startY, seg.endX, seg.endY, MIN_CROSS_FRACTION,
                ) == crossT
            }
            android.util.Log.d("XegPaperDebug", "TERMINATE via crossing, selfCrossing=$isSelf, ownSegCount=${ownSegments.size}, dist=$distanceTraveledDp, maxLen=$maxLengthDp")
        } else if (terminates) {
            android.util.Log.d("XegPaperDebug", "TERMINATE via canvas edge, ownSegCount=${ownSegments.size}, dist=$distanceTraveledDp, maxLen=$maxLengthDp")
        }

        val consumed = moveHeadAndAppend(state, nextX, nextY, fraction, step)
        if (terminates) finish(state)
        return consumed
    }

    private fun moveHeadAndAppend(state: DrawState, nextX: Float, nextY: Float, fraction: Float, step: Float): Float {
        val endX = headX + (nextX - headX) * fraction
        val endY = headY + (nextY - headY) * fraction
        val segment = LineSegment(headX, headY, endX, endY, color)
        state.segments.add(segment)
        ownSegments.add(segment)
        headX = endX
        headY = endY
        val consumed = step * fraction
        distanceTraveledDp += consumed / state.density
        return consumed
    }

    private fun finish(state: DrawState) {
        active = false
        // A line that stopped the moment it started leaves a zero-length stub behind,
        // which would render as a stray dot.
        val last = ownSegments.lastOrNull() ?: return
        if (abs(last.endX - last.startX) < DEGENERATE_PX && abs(last.endY - last.startY) < DEGENERATE_PX) {
            state.segments.remove(last)
            ownSegments.removeAt(ownSegments.size - 1)
        }
    }

    /** Fraction of [step] at which the head would leave the surface, or null if it stays. */
    private fun boundsFraction(state: DrawState, nextX: Float, nextY: Float): Float? {
        var earliest = Float.MAX_VALUE
        if (nextX < 0f) earliest = min(earliest, (0f - headX) / (nextX - headX))
        if (nextX > state.width) earliest = min(earliest, (state.width - headX) / (nextX - headX))
        if (nextY < 0f) earliest = min(earliest, (0f - headY) / (nextY - headY))
        if (nextY > state.height) earliest = min(earliest, (state.height - headY) / (nextY - headY))
        return if (earliest == Float.MAX_VALUE) null else earliest.coerceIn(0f, 1f)
    }

    private fun lerpAngle(a: Float, b: Float, t: Float): Float {
        var diff = (b - a) % TWO_PI
        if (diff > PI_F) diff -= TWO_PI
        if (diff < -PI_F) diff += TWO_PI
        return a + diff * t
    }

    private enum class Phase { LOOP, NECK }

    private companion object {
        /** Length of each straight chord approximating the curve, in dp. */
        const val CHORD_DP = 6f

        /** Straight run from the spawn point before the first loop begins, clearing the
         *  perimeter edge or segment the line just spawned from before it starts to curve. */
        const val INITIAL_RUNUP_DP = 60f

        const val NOISE_FREQUENCY = 0.035f

        /** Chance a loop curls into a round bubble bump rather than a tapering blade. */
        const val BUBBLE_PROBABILITY = 0.5f

        /** Total turn for a bubble bump — most of a full loop, so it reads as a round
         *  bubble rather than a shallow curve. Safe to push this close to 360 degrees
         *  because [safeHeading] (not this angle) is what actually guarantees the line
         *  never crosses itself. */
        const val BUBBLE_TURN_MIN_DEG = 200f
        const val BUBBLE_TURN_MAX_DEG = 300f

        /** Distance over which a blade's curvature decays to zero, turning a curl into
         *  a straightening, pointed hook. Its cumulative turn is half of what constant
         *  curvature over the same length would give (the curvature ramps linearly to
         *  zero), so even at [LOOP_RADIUS_MIN_DP] and this range's max it stays under
         *  160 degrees — the same never-face-backward safety margin as the bubble. */
        const val BLADE_LENGTH_MIN_DP = 55f
        const val BLADE_LENGTH_MAX_DP = 110f

        /** Radius of curvature while looping, in dp — controls how tight or wide the
         *  bubbles/blades are. */
        const val LOOP_RADIUS_MIN_DP = 20f
        const val LOOP_RADIUS_MAX_DP = 42f

        /** Short straight connector between loops — the "pinch" joining one bubble or
         *  blade to the next. */
        const val NECK_MIN_DP = 8f
        const val NECK_MAX_DP = 20f

        /** Chance consecutive loops curl in opposite directions, so the line snakes
         *  back and forth rather than spiraling in on itself. */
        const val LOOP_FLIP_PROBABILITY = 0.65f

        /** Minimum distance, in dp, a looping chord must keep from any earlier stretch
         *  of this same line (see [wouldApproachOwnPath]). */
        const val SELF_CLEARANCE_DP = 20f

        /** How many of this line's own trailing segments [wouldApproachOwnPath] ignores
         *  — comfortably more than one full bubble+neck cycle's worth of chords, so the
         *  check only ever looks at genuinely earlier geometry, not the loop currently
         *  in progress. */
        const val SELF_CHECK_EXCLUDE_RECENT = 40

        /** Fraction of [maxLengthDp] at which heading starts blending toward
         *  [nearestExitPoint] instead of following the loop/neck state machine. */
        const val APPROACH_FRACTION = 0.9f

        const val MIN_CROSS_FRACTION = 1e-4f
        const val DEGENERATE_PX = 0.5f

        /** Light surface wobble on top of the loop shape — texture, not a shape driver. */
        val NOISE_AMPLITUDE_RADIANS = Math.toRadians(8.0).toFloat()
        val PI_F = PI.toFloat()
        val TWO_PI = (PI * 2.0).toFloat()
    }
}
