package com.xegster.xegpaper.engine

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Diffusion-limited aggregation: several walkers drift on an undirected, wandering
 * random walk — each chord turns by an independent random amount rather than
 * following a fixed curvature or a noise field — until they bump into existing
 * geometry or the canvas edge, via [SegmentStepper], and stick there. Restarting a
 * walker on the outer perimeter (see [PerimeterTracker]) rather than at a uniformly
 * random point is what keeps growth radiating outward from what is already drawn
 * instead of scribbling all over the open canvas.
 *
 * A walker that wanders for too long without hitting anything is gently steered
 * toward the nearest existing geometry, so it reliably joins the aggregate instead of
 * drifting until it happens to reach a wall.
 */
class AggregationLinePattern(private val random: Random = Random.Default) : PatternGenerator {

    override val type: PatternType = PatternType.AGGREGATION

    private val perimeter = PerimeterTracker(random)
    private val walkers = ArrayList<Walker>()

    override fun reset(state: DrawState, config: WallpaperConfig) {
        state.clear()
        walkers.clear()
    }

    override fun isSettled(): Boolean = walkers.isEmpty()

    override fun update(state: DrawState, config: WallpaperConfig, deltaSeconds: Float) {
        if (!state.hasSurface || deltaSeconds <= 0f) return

        ensureWalkers(state, config)
        if (walkers.isEmpty()) return

        val budget = config.speedDpPerSecond * state.density * deltaSeconds
        val perWalker = budget / walkers.size
        for (index in walkers.indices.reversed()) {
            if (stepWalker(state, config, walkers[index], perWalker)) walkers.removeAt(index)
        }
    }

    private fun ensureWalkers(state: DrawState, config: WallpaperConfig) {
        while (walkers.size < ACTIVE_WALKER_COUNT && state.segments.size < config.maxSegments) {
            val spawn = perimeter.randomSpawnPoint(state)
            walkers.add(Walker(spawn[0], spawn[1], spawn[2]))
        }
    }

    /** Advances [walker] by up to [budgetPx]. Returns true once it has stuck to
     *  something or left the canvas. */
    private fun stepWalker(state: DrawState, config: WallpaperConfig, walker: Walker, budgetPx: Float): Boolean {
        var remaining = budgetPx
        while (remaining > 0f) {
            val chordPx = min(remaining, CHORD_DP * state.density)
            if (chordPx <= 0f) return false

            walker.angle = steeredAngle(state, walker)
            val nextX = walker.x + cos(walker.angle) * chordPx
            val nextY = walker.y + sin(walker.angle) * chordPx

            val result = SegmentStepper.step(state, walker.x, walker.y, nextX, nextY)
            state.segments.add(LineSegment(walker.x, walker.y, result.endX, result.endY, config.lineColor))

            val consumedPx = chordPx * result.fraction
            remaining -= consumedPx
            walker.x = result.endX
            walker.y = result.endY
            walker.traveledDp += consumedPx / state.density

            if (result.terminated) return true
        }
        return false
    }

    /** The undirected random turn that makes this a random walk, blended toward the
     *  nearest existing geometry once the walker has wandered past [STEER_START_DP]
     *  without sticking to anything. */
    private fun steeredAngle(state: DrawState, walker: Walker): Float {
        val turn = (random.nextFloat() - 0.5f) * 2f * MAX_TURN_RADIANS
        val wandered = walker.angle + turn
        if (walker.traveledDp < STEER_START_DP) return wandered

        val target = nearestPoint(state, walker.x, walker.y) ?: return wandered
        val towardTarget = atan2(target[1] - walker.y, target[0] - walker.x)
        val steer = ((walker.traveledDp - STEER_START_DP) / STEER_RAMP_DP).coerceIn(0f, 1f)
        return lerpAngle(wandered, towardTarget, steer)
    }

    private fun nearestPoint(state: DrawState, x: Float, y: Float): FloatArray? {
        var bestDistance = Float.MAX_VALUE
        var best: FloatArray? = null
        for (segment in state.segments) {
            val point = IntersectionDetector.closestPointOnSegment(segment, x, y)
            val distance = hypot(point[0] - x, point[1] - y)
            if (distance < bestDistance) {
                bestDistance = distance
                best = point
            }
        }
        return best
    }

    private fun lerpAngle(a: Float, b: Float, t: Float): Float {
        var diff = (b - a) % TWO_PI
        if (diff > PI_F) diff -= TWO_PI
        if (diff < -PI_F) diff += TWO_PI
        return a + diff * t
    }

    private class Walker(var x: Float, var y: Float, var angle: Float, var traveledDp: Float = 0f)

    private companion object {
        /** Length of each straight chord of the random walk, in dp. */
        const val CHORD_DP = 7f

        /** Walkers drifting in parallel; also the density multiplier. */
        const val ACTIVE_WALKER_COUNT = 3

        /** Maximum random turn applied per chord, in radians. */
        val MAX_TURN_RADIANS = Math.toRadians(50.0).toFloat()

        /** Distance a walker can wander before it starts being steered toward the
         *  aggregate, so it reliably sticks rather than drifting to the canvas edge. */
        const val STEER_START_DP = 90f
        const val STEER_RAMP_DP = 140f

        val PI_F = PI.toFloat()
        val TWO_PI = (PI * 2.0).toFloat()
    }
}
