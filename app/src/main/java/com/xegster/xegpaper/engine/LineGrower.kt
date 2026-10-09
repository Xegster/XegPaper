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
 * A bubble's total turn (see [Style.bubbleTurnMaxDeg]) is most of a full loop, so it reads
 * as a round bump rather than a shallow curve — capping the angle is *not* what keeps
 * it from crossing itself, though: two consecutive loops curling in opposite directions
 * can cross each other even when neither individually turns anywhere near a full
 * circle. So every chord — whatever produced its heading, including the exit-steering
 * blend near max length, which otherwise could point straight back through the line's
 * own tangle — passes through [safeHeading], which nudges it away from anything within
 * [SELF_CLEARANCE_DP] of an *earlier* stretch of this same line. When [safeHeading]
 * finds no clear direction at all — the head boxed in by its own path on every side,
 * which can still happen after enough tightly-wound loops — [step] stops the line right
 * there rather than drawing the only heading left. Between the nudge and that hard
 * stop, the line can only ever end by touching *other* geometry or the canvas edge,
 * never itself.
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
    private val style: Style = Style.FLOWING,
) {
    private val random = random
    private val noise = PerlinNoise(random)

    /** This line's own segments in growth order. Used two ways: [nearestExitPoint]
     *  excludes all of them (a point on the line's own tail would otherwise trivially
     *  "win" at distance zero), while [wouldApproachOwnPath] checks the *earlier* ones —
     *  everything before [recentWindowStart] — to keep the line from curling back into
     *  itself. */
    private val ownSegments = ArrayList<LineSegment>()

    /** Index of the oldest segment still within [SELF_CHECK_EXCLUDE_RECENT_DP] of the
     *  head, measured along the drawn path (not segment *count* — see
     *  [updateRecentWindow] for why that distinction matters). Segments from this index
     *  onward are "recent" and excluded from [wouldApproachOwnPath]; everything before
     *  it is fair game to check. */
    private var recentWindowStart = 0

    /** Sum of segment lengths (px) from [recentWindowStart] to the most recent segment —
     *  kept incrementally in [updateRecentWindow] so finding the window is O(1)
     *  amortized instead of re-summing from the tail on every check. */
    private var recentWindowLengthPx = 0f

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

    /** Current tip position and heading, in surface pixels/radians. Lets a pattern
     *  spawn new growth from a growing line's own tip instead of only from the
     *  perimeter — see [BloomPattern]. */
    val tipX: Float get() = headX
    val tipY: Float get() = headY
    val tipHeading: Float get() = currentHeading

    /**
     * Moves the head to ([x], [y]) heading [heading] and starts a fresh neck from
     * there, as if this grower had just finished an ordinary chord — without drawing
     * anything itself. For a pattern that draws other geometry (like a scythe) into
     * the same path and then hands growth back to this grower from where that
     * geometry ended, so the whole thing reads as one continuous line instead of two
     * disconnected pieces — see [ScytheBubblePattern]. [travelledDp] is added to this
     * grower's own travelled distance, so the detour still counts against
     * [maxLengthDp] and the exit-steering in [headingFor] still kicks in on schedule.
     */
    fun warpTo(x: Float, y: Float, heading: Float, travelledDp: Float) {
        headX = x
        headY = y
        currentHeading = heading
        chordRemainingPx = 0f
        distanceTraveledDp += travelledDp
        startNeck()
    }

    /** Advances up to [budgetPx] worth of drawing, stopping early if the line
     *  terminates. Returns the px actually consumed. */
    fun step(state: DrawState, budgetPx: Float): Float {
        var remaining = budgetPx
        var consumedTotal = 0f
        while (remaining > 0f && active) {
            if (chordRemainingPx <= 0f && !beginChord(state)) {
                // Boxed in on every side by this line's own earlier path, with no
                // heading left that doesn't approach it — see [safeHeading]. Stopping
                // here, without drawing this chord, is what makes "never crosses
                // itself" an absolute guarantee rather than just the common case.
                android.util.Log.d("XegPaperDebug", "TERMINATE via boxed-in safety valve, ownSegCount=${ownSegments.size}, dist=$distanceTraveledDp, maxLen=$maxLengthDp")
                finish(state)
                break
            }
            val subStep = min(remaining, chordRemainingPx)
            if (subStep <= 0f) break
            val consumed = advance(state, subStep)
            remaining -= consumed
            chordRemainingPx -= consumed
            consumedTotal += consumed
        }
        return consumedTotal
    }

    /** Starts the next chord. Returns false (leaving [chordRemainingPx] at 0) if no
     *  heading is safe to draw at all. */
    private fun beginChord(state: DrawState): Boolean {
        advancePhase(state)
        val safe = safeHeading(state, headingFor(state)) ?: return false
        chordAngle = safe
        chordRemainingPx = CHORD_DP * state.density
        return true
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
     *  [SELF_CHECK_EXCLUDE_RECENT_DP] *of path distance* back (see
     *  [recentWindowStart]/[updateRecentWindow]) — the trailing stretch is excluded
     *  because a curling loop is naturally within a radius's distance of its own last
     *  bit of path; that is not a risk, only an approach to genuinely *earlier*
     *  geometry is. Uses the exact segment-to-segment distance (see
     *  [IntersectionDetector.distanceBetweenSegments]) rather than sampling a couple of
     *  points along the chord, which could pass an old segment closely enough to
     *  actually cross it without either sampled point being the closest approach. */
    private fun wouldApproachOwnPath(state: DrawState, candidateHeading: Float): Boolean {
        if (recentWindowStart <= 0) return false
        val chordPx = CHORD_DP * state.density
        val lookaheadX = headX + cos(candidateHeading) * chordPx
        val lookaheadY = headY + sin(candidateHeading) * chordPx
        val clearancePx = SELF_CLEARANCE_DP * state.density
        for (index in 0 until recentWindowStart) {
            val segment = ownSegments[index]
            val d = IntersectionDetector.distanceBetweenSegments(headX, headY, lookaheadX, lookaheadY, segment)
            if (d < clearancePx) return true
        }
        return false
    }

    /** Last-resort gate applied to every chord regardless of what produced it (loop
     *  curvature, a straight neck, or the exit-steering blend near max length): if
     *  [desired] would approach this line's own earlier path, rotate it in increasing
     *  10-degree steps, alternating sides, until a clear direction is found. This is
     *  what keeps *exit-steering* honest too — without it, heading straight for the
     *  nearest edge or line could cut straight back through a tangle of this line's own
     *  earlier loops. Returns null if literally every direction is blocked — the head
     *  boxed in by its own path on all sides, which [step] treats as a hard stop rather
     *  than falling through to an actual self-crossing. */
    private fun safeHeading(state: DrawState, desired: Float): Float? {
        if (!wouldApproachOwnPath(state, desired)) return desired
        for (step in 1..18) {
            val delta = Math.toRadians((step * 10).toDouble()).toFloat()
            val plus = desired + delta
            if (!wouldApproachOwnPath(state, plus)) return plus
            val minus = desired - delta
            if (!wouldApproachOwnPath(state, minus)) return minus
        }
        return null
    }

    private fun startNewLoop() {
        isBubble = random.nextFloat() < style.bubbleProbability
        val radiusDp = style.loopRadiusMinDp + random.nextFloat() * (style.loopRadiusMaxDp - style.loopRadiusMinDp)
        loopCurvaturePerDp = 1f / radiusDp
        if (isBubble) {
            val turnDegrees = style.bubbleTurnMinDeg + random.nextFloat() * (style.bubbleTurnMaxDeg - style.bubbleTurnMinDeg)
            loopTurnRemainingRadians = Math.toRadians(turnDegrees.toDouble()).toFloat()
        } else {
            bladeLengthDp = style.bladeLengthMinDp + random.nextFloat() * (style.bladeLengthMaxDp - style.bladeLengthMinDp)
            bladeTraveledDp = 0f
        }
        if (random.nextFloat() < style.loopFlipProbability) loopDirection = -loopDirection
        phase = Phase.LOOP
    }

    private fun startNeck() {
        neckRemainingDp = style.neckMinDp + random.nextFloat() * (style.neckMaxDp - style.neckMinDp)
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
        updateRecentWindow(state, segment)
        headX = endX
        headY = endY
        val consumed = step * fraction
        distanceTraveledDp += consumed / state.density
        return consumed
    }

    /** Slides [recentWindowStart] forward to keep [recentWindowLengthPx] just over
     *  [SELF_CHECK_EXCLUDE_RECENT_DP] worth of *actual path distance*, not segment
     *  count. Segment count is not a safe proxy for distance here: a per-frame draw
     *  budget smaller than [CHORD_DP] (the common case at ordinary speeds and frame
     *  rates) splits a single logical chord across several frames, each appending its
     *  own short [LineSegment] — so a fixed *count* of "recent" segments can span a
     *  wildly different real distance depending on frame timing, and a straight run can
     *  end up "approaching" a point on itself that is actually still ahead of where a
     *  real local-curvature exclusion should reach. */
    private fun updateRecentWindow(state: DrawState, added: LineSegment) {
        recentWindowLengthPx += hypot(added.endX - added.startX, added.endY - added.startY)
        val thresholdPx = SELF_CHECK_EXCLUDE_RECENT_DP * state.density
        while (recentWindowStart < ownSegments.size - 1) {
            val oldest = ownSegments[recentWindowStart]
            val oldestLength = hypot(oldest.endX - oldest.startX, oldest.endY - oldest.startY)
            if (recentWindowLengthPx - oldestLength < thresholdPx) break
            recentWindowLengthPx -= oldestLength
            recentWindowStart++
        }
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

    /**
     * Tunes the loop/neck state machine's shape — the growth, clipping, and
     * self-avoidance mechanics documented on the class above apply identically
     * regardless of style. [FLOWING] is the seed-growth design's original even mix of
     * bubbles and blades; [BLOOM] leans hard toward bubbles for [BloomPattern].
     */
    data class Style(
        /** Chance a loop curls into a round bubble bump rather than a tapering blade. */
        val bubbleProbability: Float = 0.5f,
        /** Total turn for a bubble bump, in degrees — most of a full loop, so it reads
         *  as a round bubble rather than a shallow curve. Safe to push this close to
         *  360 degrees because [safeHeading] (not this angle) is what actually
         *  guarantees the line never crosses itself. */
        val bubbleTurnMinDeg: Float = 200f,
        val bubbleTurnMaxDeg: Float = 300f,
        /** Distance over which a blade's curvature decays to zero, turning a curl into
         *  a straightening, pointed hook, in dp. */
        val bladeLengthMinDp: Float = 55f,
        val bladeLengthMaxDp: Float = 110f,
        /** Radius of curvature while looping, in dp — controls how tight or wide the
         *  bubbles/blades are. */
        val loopRadiusMinDp: Float = 20f,
        val loopRadiusMaxDp: Float = 42f,
        /** Short straight connector between loops — the "pinch" joining one bubble or
         *  blade to the next, in dp. */
        val neckMinDp: Float = 8f,
        val neckMaxDp: Float = 20f,
        /** Chance consecutive loops curl in opposite directions, so the line snakes
         *  back and forth rather than spiraling in on itself. */
        val loopFlipProbability: Float = 0.65f,
    ) {
        companion object {
            /** The seed-growth design's original even mix of bubbles and blades. */
            val FLOWING = Style()

            /** Heavily biased toward bubbles, with shorter necks so they read as a
             *  dense cluster of blooms rather than occasional bumps. */
            val BLOOM = Style(bubbleProbability = 0.85f, neckMinDp = 6f, neckMaxDp = 14f)
        }
    }

    private companion object {
        /** Length of each straight chord approximating the curve, in dp. */
        const val CHORD_DP = 6f

        /** Straight run from the spawn point before the first loop begins, clearing the
         *  perimeter edge or segment the line just spawned from before it starts to curve. */
        const val INITIAL_RUNUP_DP = 60f

        const val NOISE_FREQUENCY = 0.035f

        /** Minimum distance, in dp, a looping chord must keep from any earlier stretch
         *  of this same line (see [wouldApproachOwnPath]). */
        const val SELF_CLEARANCE_DP = 26f

        /** How much of this line's own trailing *path distance* (dp, not segment count
         *  — see [updateRecentWindow]) [wouldApproachOwnPath] ignores. This must only
         *  cover *local* curvature adjacency — two points a short arc apart on the same
         *  smooth curve are naturally within [SELF_CLEARANCE_DP] of each other, which is
         *  not a risk. At [SELF_CLEARANCE_DP] = 26dp, two points on an arc of any radius
         *  in [Style.FLOWING]'s [loopRadiusMinDp][Style.loopRadiusMinDp]..
         *  [loopRadiusMaxDp][Style.loopRadiusMaxDp] range stay within that distance for
         *  no more than ~28dp of arc-length, so this only needs a modest safety margin
         *  above that. It must NOT be large enough to cover a whole bubble (up to ~200dp of arc
         *  for a wide, nearly-closed loop) — that was the actual bug behind a handful of
         *  real self-crossings: it hid a loop's closing approach toward its *own start*
         *  from the safety check entirely, because that start was still within the
         *  "recent, ignore it" window. */
        const val SELF_CHECK_EXCLUDE_RECENT_DP = 36f

        /** Fraction of [maxLengthDp] at which heading starts blending toward
         *  [nearestExitPoint] instead of following the loop/neck state machine. Kept
         *  well below 1 — not just 0.9 — because by the time a bubble-heavy line is
         *  that close to its cap it has built up a dense tangle of its own past path,
         *  and [safeHeading]'s single-chord nudge can occasionally find no clear
         *  direction left toward the exit (a self-crossing last resort). Starting the
         *  blend earlier gives it room to head out while the tangle is still sparse. */
        const val APPROACH_FRACTION = 0.6f

        const val MIN_CROSS_FRACTION = 1e-4f
        const val DEGENERATE_PX = 0.5f

        /** Light surface wobble on top of the loop shape — texture, not a shape driver. */
        val NOISE_AMPLITUDE_RADIANS = Math.toRadians(8.0).toFloat()
        val PI_F = PI.toFloat()
        val TWO_PI = (PI * 2.0).toFloat()
    }
}
