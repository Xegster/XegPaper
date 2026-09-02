package com.xegster.xegpaper.engine

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Picks spawn points for new seeds from the "outer perimeter": the four canvas edges
 * plus every line segment already drawn. A finished line becomes part of the
 * perimeter the moment it is added to [DrawState.segments] — nothing extra has to be
 * tracked here — which is what makes new growth radiate outward from what already
 * exists instead of spawning in open space.
 */
class PerimeterTracker(private val random: Random) {

    /** A random point on the perimeter, as `[x, y, headingRadians]`, heading away from
     *  it so the new line opens into clear space instead of immediately re-crossing
     *  what it spawned from. */
    fun randomSpawnPoint(state: DrawState): FloatArray {
        repeat(SPAWN_ATTEMPTS) {
            val candidate = if (state.segments.isEmpty() || random.nextFloat() < EDGE_SPAWN_PROBABILITY) {
                pointOnCanvasEdge(state)
            } else {
                pointOnExistingSegment(state)
            }
            if (headsCleanly(state, candidate)) return candidate
        }
        return pointOnCanvasEdge(state)
    }

    private fun pointOnCanvasEdge(state: DrawState): FloatArray {
        val margin = EDGE_MARGIN_DP * state.density
        val usableWidth = (state.width - margin * 2f).coerceAtLeast(1f)
        val usableHeight = (state.height - margin * 2f).coerceAtLeast(1f)
        val spread = (random.nextFloat() - 0.5f) * ANGLE_SPREAD
        return when (random.nextInt(4)) {
            0 -> floatArrayOf(margin + random.nextFloat() * usableWidth, 0f, HALF_PI + spread)
            1 -> floatArrayOf(state.width, margin + random.nextFloat() * usableHeight, PI_F + spread)
            2 -> floatArrayOf(margin + random.nextFloat() * usableWidth, state.height, -HALF_PI + spread)
            else -> floatArrayOf(0f, margin + random.nextFloat() * usableHeight, spread)
        }
    }

    /** A random point along a random existing segment, heading out along its normal. */
    private fun pointOnExistingSegment(state: DrawState): FloatArray {
        val segments = state.segments
        val segment = segments[random.nextInt(segments.size)]
        val t = random.nextFloat()
        val x = segment.startX + (segment.endX - segment.startX) * t
        val y = segment.startY + (segment.endY - segment.startY) * t
        val tangent = atan2(segment.endY - segment.startY, segment.endX - segment.startX)
        val normalSign = if (random.nextBoolean()) 1f else -1f
        val spread = (random.nextFloat() - 0.5f) * ANGLE_SPREAD
        return floatArrayOf(x, y, tangent + normalSign * HALF_PI + spread)
    }

    /** True when a short step from the candidate stays in bounds and does not
     *  immediately cross back into existing geometry. */
    private fun headsCleanly(state: DrawState, candidate: FloatArray): Boolean {
        val (x, y, angle) = candidate
        val probe = SPAWN_LOOKAHEAD_DP * state.density
        val nx = x + cos(angle) * probe
        val ny = y + sin(angle) * probe
        if (nx !in 0f..state.width || ny !in 0f..state.height) return false
        return IntersectionDetector.firstCrossFraction(state.segments, x, y, nx, ny, MIN_CROSS_FRACTION) == null
    }

    private companion object {
        /** Chance a spawn point lands on a canvas edge rather than existing geometry,
         *  even once lines exist, so growth keeps entering from the border too. */
        const val EDGE_SPAWN_PROBABILITY = 0.35f

        const val EDGE_MARGIN_DP = 12f
        const val ANGLE_SPREAD = 1.2f
        const val SPAWN_ATTEMPTS = 16
        const val SPAWN_LOOKAHEAD_DP = 20f
        const val MIN_CROSS_FRACTION = 1e-4f

        val HALF_PI = (PI / 2.0).toFloat()
        val PI_F = PI.toFloat()
    }
}
