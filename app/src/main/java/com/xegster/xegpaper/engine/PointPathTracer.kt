package com.xegster.xegpaper.engine

import kotlin.math.hypot

/**
 * Walks a precomputed polyline (interleaved x/y pixels), clipped by [SegmentStepper]
 * exactly like every other pattern here: it stops dead the instant it crosses existing
 * geometry or leaves the canvas, wherever that falls along the path.
 *
 * Used by every pattern that knows a shape's whole outline up front and only needs to
 * *reveal* it at the configured speed — [ScytheLinePattern], [ScytheBubblePattern]'s
 * scythe detours, and [MultiScythePattern]'s chains — instead of steering a head
 * chord by chord.
 */
class PointPathTracer(private val points: FloatArray) {

    private var x = points[0]
    private var y = points[1]
    private var next = 1
    private var last: LineSegment? = null

    var active = true
        private set

    /** Advances up to [budgetPx] worth of drawing, stopping early if the path
     *  terminates (finishes, crosses something, or leaves the canvas). */
    fun advance(state: DrawState, color: Int, budgetPx: Float) {
        var remaining = budgetPx
        while (remaining > MIN_STEP_PX && active) {
            val targetX = points[next * 2]
            val targetY = points[next * 2 + 1]
            val distance = hypot(targetX - x, targetY - y)
            if (distance < DEGENERATE_PX) {
                if (!moveToNextVertex()) return
                continue
            }

            val reachesVertex = distance <= remaining
            val stepX = if (reachesVertex) targetX else x + (targetX - x) / distance * remaining
            val stepY = if (reachesVertex) targetY else y + (targetY - y) / distance * remaining

            val result = SegmentStepper.step(state, x, y, stepX, stepY)
            val segment = LineSegment(x, y, result.endX, result.endY, color)
            state.segments.add(segment)
            last = segment

            remaining -= hypot(stepX - x, stepY - y) * result.fraction
            x = result.endX
            y = result.endY

            if (result.terminated) {
                finish(state)
                return
            }
            if (reachesVertex && !moveToNextVertex()) return
        }
    }

    /** Returns false once the path is complete. */
    private fun moveToNextVertex(): Boolean {
        next++
        if (next * 2 >= points.size) {
            active = false
            return false
        }
        return true
    }

    private fun finish(state: DrawState) {
        active = false
        // A path that stopped the moment it started leaves a zero-length stub behind,
        // which would render as a stray dot.
        val segment = last ?: return
        if (hypot(segment.endX - segment.startX, segment.endY - segment.startY) < DEGENERATE_PX) {
            state.segments.remove(segment)
        }
    }

    companion object {
        /** How much of [points], as a fraction of its chords, is clear of the canvas
         *  edge and of [state]'s existing geometry before something is hit. Lets a
         *  pattern score several candidate placements and keep the one that runs
         *  longest before something cuts it short.
         *
         *  [skipLastChord] is for a closed loop whose last chord returns to its own
         *  start vertex by design (see [ScytheLinePattern]) — that touch is not a
         *  crossing to score against. */
        fun clearFraction(
            state: DrawState,
            points: FloatArray,
            skipLastChord: Boolean = false,
            minCrossFraction: Float = 1e-4f,
        ): Float {
            val chords = points.size / 2 - 1
            if (chords <= 0) return 1f
            for (i in 0 until chords) {
                val ax = points[i * 2]
                val ay = points[i * 2 + 1]
                val bx = points[i * 2 + 2]
                val by = points[i * 2 + 3]
                if (bx < 0f || bx > state.width || by < 0f || by > state.height) return i.toFloat() / chords
                if (skipLastChord && i == chords - 1) break
                if (IntersectionDetector.firstCrossFraction(state.segments, ax, ay, bx, by, minCrossFraction) != null) {
                    return i.toFloat() / chords
                }
            }
            return 1f
        }

        const val DEGENERATE_PX = 0.5f

        /** Below this much budget left the step is lost in float rounding — at pixel
         *  coordinates in the hundreds it cannot move the head at all, so looping on it
         *  would never finish. */
        const val MIN_STEP_PX = 0.01f
    }
}
