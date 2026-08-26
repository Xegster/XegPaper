package com.xegster.xegpaper.engine

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Straight-line segment intersection test.
 *
 * A growing line has to stop *exactly* where it crosses an existing one, so the test
 * returns how far along the growing segment the crossing happens rather than a plain
 * boolean.
 */
object IntersectionDetector {

    private const val EPSILON = 1e-6f

    /**
     * Fraction along `a -> b` (in `[minT, 1]`) where that segment first crosses
     * `c -> d`, or `null` when they do not cross.
     *
     * Parallel and collinear segments count as "no crossing": a line that runs along
     * an existing one has nowhere meaningful to stop.
     *
     * [minT] lets the caller ignore a touch right at `a`, which happens constantly
     * because consecutive segments of a polyline share a vertex.
     */
    fun crossFraction(
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        cx: Float,
        cy: Float,
        dx: Float,
        dy: Float,
        minT: Float = 0f,
    ): Float? {
        val rx = bx - ax
        val ry = by - ay
        val sx = dx - cx
        val sy = dy - cy

        val denominator = rx * sy - ry * sx
        if (abs(denominator) < EPSILON) return null

        val qpx = cx - ax
        val qpy = cy - ay

        val t = (qpx * sy - qpy * sx) / denominator
        if (t < minT || t > 1f) return null

        val u = (qpx * ry - qpy * rx) / denominator
        if (u < 0f || u > 1f) return null

        return t
    }

    /**
     * First crossing of `a -> b` against every segment in [segments].
     *
     * Segments that touch `a` exactly are ignored: consecutive pieces of a polyline
     * share a vertex, and the growing segment itself always ends at `a`. Those
     * coordinates are copied verbatim when a segment is started, so the comparison is
     * an exact float match rather than a tolerance.
     */
    fun firstCrossFraction(
        segments: List<LineSegment>,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        minT: Float = 0f,
    ): Float? {
        var nearest: Float? = null
        for (index in segments.indices) {
            val segment = segments[index]
            if (segment.startX == ax && segment.startY == ay) continue
            if (segment.endX == ax && segment.endY == ay) continue
            val t = crossFraction(
                ax, ay, bx, by,
                segment.startX, segment.startY, segment.endX, segment.endY,
                minT,
            ) ?: continue
            if (nearest == null || t < nearest) nearest = t
        }
        return nearest
    }

    /** Shortest distance from ([px], [py]) to [segment]. */
    fun distanceToSegment(segment: LineSegment, px: Float, py: Float): Float {
        val dx = segment.endX - segment.startX
        val dy = segment.endY - segment.startY
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared < EPSILON) return hypot(px - segment.startX, py - segment.startY)

        val t = (((px - segment.startX) * dx + (py - segment.startY) * dy) / lengthSquared)
            .coerceIn(0f, 1f)
        return hypot(px - (segment.startX + dx * t), py - (segment.startY + dy * t))
    }

    /** Shortest distance from ([px], [py]) to any of [segments], or [Float.MAX_VALUE]. */
    fun distanceToNearest(segments: List<LineSegment>, px: Float, py: Float): Float {
        var nearest = Float.MAX_VALUE
        for (index in segments.indices) {
            val distance = distanceToSegment(segments[index], px, py)
            if (distance < nearest) nearest = distance
        }
        return nearest
    }
}
