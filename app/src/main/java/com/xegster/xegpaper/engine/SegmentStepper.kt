package com.xegster.xegpaper.engine

import kotlin.math.min

/**
 * Shared "advance a straight step, stop exactly at whatever is hit first" logic used
 * by every pattern that grows lines from a moving head: stop at the first crossing
 * with existing geometry, or at the canvas edge, whichever comes first along the step.
 *
 * Every new pattern added after [GrowingLinePattern] and [LineGrower] needs this same
 * clipping, so it lives here once rather than being re-derived per pattern.
 */
object SegmentStepper {

    /** [endX]/[endY] are where the head actually lands (short of [nextX]/[nextY] when
     *  [terminated] is true); [fraction] is how much of the attempted step was used. */
    class Result(val endX: Float, val endY: Float, val fraction: Float, val terminated: Boolean)

    /**
     * Steps from ([headX], [headY]) toward ([nextX], [nextY]), clipped to the first
     * crossing with [DrawState.segments] or the canvas bounds.
     *
     * [minCrossFraction] ignores a crossing right at the head itself, which happens
     * constantly because consecutive chords of the same growing line share a vertex —
     * see the note on this in [IntersectionDetector].
     */
    fun step(
        state: DrawState,
        headX: Float,
        headY: Float,
        nextX: Float,
        nextY: Float,
        minCrossFraction: Float = 1e-4f,
    ): Result {
        val crossT = IntersectionDetector.firstCrossFraction(
            state.segments, headX, headY, nextX, nextY, minCrossFraction,
        )
        val edgeT = boundsFraction(state, headX, headY, nextX, nextY)

        val fraction = when {
            crossT != null && (edgeT == null || crossT <= edgeT) -> crossT
            edgeT != null -> edgeT
            else -> return Result(nextX, nextY, 1f, false)
        }
        return Result(
            headX + (nextX - headX) * fraction,
            headY + (nextY - headY) * fraction,
            fraction,
            true,
        )
    }

    /** Fraction of the step at which the head would leave the surface, or null if it stays. */
    private fun boundsFraction(state: DrawState, headX: Float, headY: Float, nextX: Float, nextY: Float): Float? {
        var earliest = Float.MAX_VALUE
        if (nextX < 0f) earliest = min(earliest, (0f - headX) / (nextX - headX))
        if (nextX > state.width) earliest = min(earliest, (state.width - headX) / (nextX - headX))
        if (nextY < 0f) earliest = min(earliest, (0f - headY) / (nextY - headY))
        if (nextY > state.height) earliest = min(earliest, (state.height - headY) / (nextY - headY))
        return if (earliest == Float.MAX_VALUE) null else earliest.coerceIn(0f, 1f)
    }
}
