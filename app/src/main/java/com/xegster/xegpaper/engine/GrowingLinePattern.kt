package com.xegster.xegpaper.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Shared machinery for every pattern that draws by growing a single line head across
 * the surface: advancing the head, stopping it the instant it crosses an existing
 * line, and stopping it at the screen edge.
 *
 * Subclasses only decide *where lines begin and how they turn* — see [startNextLine],
 * [onRunComplete] and [onEdgeReached].
 *
 * The head is deliberately modelled as one object rather than a list. Multiple
 * simultaneous heads (and touch-seeded ones) are a planned follow-up; they slot in by
 * promoting these fields into a `Walker` list without the renderer or the wallpaper
 * service noticing.
 */
abstract class GrowingLinePattern(protected val random: Random = Random.Default) : PatternGenerator {

    /** Current tip of the line being drawn, in pixels. */
    protected var headX: Float = 0f
        private set

    protected var headY: Float = 0f
        private set

    /** Direction the head is travelling, in radians. */
    protected var angleRadians: Float = 0f
        private set

    /** True while a line is actively growing. */
    protected var drawing: Boolean = false
        private set

    private var current: LineSegment? = null
    private var runRemaining: Float = 0f

    final override fun reset(state: DrawState, config: WallpaperConfig) {
        state.clear()
        current = null
        drawing = false
        runRemaining = 0f
        onReset(state, config)
    }

    final override fun update(state: DrawState, config: WallpaperConfig, deltaSeconds: Float) {
        if (!state.hasSurface || deltaSeconds <= 0f) return

        var budget = config.speedDpPerSecond * state.density * deltaSeconds
        val maxStep = MAX_STEP_DP * state.density
        var steps = 0

        while (budget > 0f && steps++ < MAX_STEPS_PER_FRAME) {
            if (!drawing && !startNextLine(state, config)) return
            if (runRemaining <= 0f) {
                onRunComplete(state, config)
                continue
            }
            val step = min(budget, min(runRemaining, maxStep))
            if (step <= 0f) return
            budget -= step
            runRemaining -= step
            extend(state, config, step)
        }
    }

    /** Hook for subclass state that has to be thrown away alongside the canvas. */
    protected open fun onReset(state: DrawState, config: WallpaperConfig) = Unit

    /**
     * Places the next line. Returns false to draw nothing this frame (for example
     * while the surface is still too small to pick a start point).
     */
    protected abstract fun startNextLine(state: DrawState, config: WallpaperConfig): Boolean

    /** Called when a segment has run its full length. Ends the line by default. */
    protected open fun onRunComplete(state: DrawState, config: WallpaperConfig) = finishLine(state)

    /** Called when the head reaches the screen edge. Ends the line by default. */
    protected open fun onEdgeReached(state: DrawState, config: WallpaperConfig) = finishLine(state)

    /** Starts a brand new line at ([x], [y]) heading along [angle] for [runLength] px. */
    protected fun beginLine(
        state: DrawState,
        config: WallpaperConfig,
        x: Float,
        y: Float,
        angle: Float,
        runLength: Float,
    ) {
        headX = x
        headY = y
        drawing = true
        beginSegment(state, config, angle, runLength)
    }

    /** Turns the current line: starts a fresh segment from the head along [angle]. */
    protected fun beginSegment(
        state: DrawState,
        config: WallpaperConfig,
        angle: Float,
        runLength: Float,
    ) {
        angleRadians = angle
        runRemaining = runLength
        val segment = LineSegment(headX, headY, headX, headY, config.lineColor)
        state.segments.add(segment)
        current = segment
    }

    /** Ends the current line; the next [update] will ask for a new one. */
    protected fun finishLine(state: DrawState) {
        // A line that stopped the moment it started leaves a zero-length stub behind,
        // which would render as a stray dot.
        val segment = current
        if (segment != null && abs(segment.endX - segment.startX) < DEGENERATE_PX &&
            abs(segment.endY - segment.startY) < DEGENERATE_PX
        ) {
            state.segments.remove(segment)
        }
        drawing = false
        current = null
        runRemaining = 0f
    }

    private fun extend(state: DrawState, config: WallpaperConfig, step: Float) {
        val segment = current ?: return
        val nextX = headX + cos(angleRadians) * step
        val nextY = headY + sin(angleRadians) * step

        val crossT = IntersectionDetector.firstCrossFraction(
            state.segments, headX, headY, nextX, nextY, MIN_CROSS_FRACTION,
        )
        val edgeT = boundsFraction(state, nextX, nextY)

        when {
            crossT != null && (edgeT == null || crossT <= edgeT) -> {
                moveHead(segment, nextX, nextY, crossT)
                finishLine(state)
            }

            edgeT != null -> {
                moveHead(segment, nextX, nextY, edgeT)
                onEdgeReached(state, config)
            }

            else -> moveHead(segment, nextX, nextY, 1f)
        }
    }

    private fun moveHead(segment: LineSegment, nextX: Float, nextY: Float, fraction: Float) {
        headX += (nextX - headX) * fraction
        headY += (nextY - headY) * fraction
        segment.endX = headX
        segment.endY = headY
    }

    /** Fraction of the step at which the head leaves the surface, or null if it stays. */
    private fun boundsFraction(state: DrawState, nextX: Float, nextY: Float): Float? {
        var earliest = Float.MAX_VALUE
        if (nextX < 0f) earliest = min(earliest, (0f - headX) / (nextX - headX))
        if (nextX > state.width) earliest = min(earliest, (state.width - headX) / (nextX - headX))
        if (nextY < 0f) earliest = min(earliest, (0f - headY) / (nextY - headY))
        if (nextY > state.height) earliest = min(earliest, (state.height - headY) / (nextY - headY))
        return if (earliest == Float.MAX_VALUE) null else earliest.coerceIn(0f, 1f)
    }

    /**
     * Picks a start point that is at least [clearanceDp] away from every drawn line,
     * so a new line does not begin sitting on top of an old one. Falls back to the
     * roomiest candidate it found once the surface gets crowded.
     */
    protected fun pickOpenPoint(state: DrawState, clearanceDp: Float = START_CLEARANCE_DP): FloatArray {
        val margin = START_MARGIN_DP * state.density
        val clearance = clearanceDp * state.density
        val usableWidth = (state.width - margin * 2f).coerceAtLeast(1f)
        val usableHeight = (state.height - margin * 2f).coerceAtLeast(1f)

        var bestX = 0f
        var bestY = 0f
        var bestClearance = -1f
        repeat(START_POINT_ATTEMPTS) {
            val x = margin + random.nextFloat() * usableWidth
            val y = margin + random.nextFloat() * usableHeight
            val distance = IntersectionDetector.distanceToNearest(state.segments, x, y)
            if (distance >= clearance) return floatArrayOf(x, y)
            if (distance > bestClearance) {
                bestClearance = distance
                bestX = x
                bestY = y
            }
        }
        return floatArrayOf(bestX, bestY)
    }

    /** True when a short step along [angle] from the head stays on the surface. */
    protected fun headsInward(state: DrawState, angle: Float): Boolean {
        val probe = EDGE_PROBE_DP * state.density
        val x = headX + cos(angle) * probe
        val y = headY + sin(angle) * probe
        return x in 0f..state.width && y in 0f..state.height
    }

    protected companion object {
        /**
         * Longest single extension, in dp. Capping it keeps the intersection test
         * accurate: a fast line still checks for crossings several times per frame
         * instead of jumping over a line it should have stopped at.
         */
        const val MAX_STEP_DP = 14f

        /** Safety net so an oddly long frame cannot spin the loop forever. */
        const val MAX_STEPS_PER_FRAME = 96

        /** Ignores crossings that sit on the head itself. */
        const val MIN_CROSS_FRACTION = 1e-4f

        const val EDGE_PROBE_DP = 6f

        /** Below this length a segment is a dot, not a line. */
        const val DEGENERATE_PX = 0.5f

        const val START_MARGIN_DP = 12f
        const val START_CLEARANCE_DP = 18f
        const val START_POINT_ATTEMPTS = 24
    }
}
