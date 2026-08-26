package com.xegster.xegpaper.engine

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A single line walking the screen on a fixed angle grid: it runs straight for a
 * while, turns by a multiple of 45 degrees, bounces off the screen edges, and stops
 * dead the moment it crosses a line that is already there. A new line then starts
 * somewhere with room around it.
 */
class GeometricLinePattern(random: Random = Random.Default) : GrowingLinePattern(random) {

    override val type: PatternType = PatternType.ANGULAR

    private var segmentsInLine = 0

    override fun onReset(state: DrawState, config: WallpaperConfig) {
        segmentsInLine = 0
    }

    override fun startNextLine(state: DrawState, config: WallpaperConfig): Boolean {
        val point = pickOpenPoint(state)
        segmentsInLine = 1
        beginLine(state, config, point[0], point[1], randomGridAngle(), randomRunLength(state))
        return true
    }

    override fun onRunComplete(state: DrawState, config: WallpaperConfig) {
        if (segmentsInLine >= MAX_SEGMENTS_PER_LINE) {
            finishLine(state)
            return
        }
        segmentsInLine++
        beginSegment(state, config, turnFrom(angleRadians), randomRunLength(state))
    }

    override fun onEdgeReached(state: DrawState, config: WallpaperConfig) {
        // Bounce back inside rather than ending here: edges are not intersections, and
        // a line that dies at every wall leaves the middle of the screen empty.
        if (segmentsInLine >= MAX_SEGMENTS_PER_LINE) {
            finishLine(state)
            return
        }
        // Doubling back would retrace the segment just drawn, which reads as the line
        // freezing at the wall, so that direction is off the table.
        val reverseStep = (gridStepOf(angleRadians) + GRID_DIRECTIONS / 2) % GRID_DIRECTIONS
        val inwardAngle = (0 until GRID_DIRECTIONS)
            .filter { it != reverseStep }
            .map { it * GRID_STEP }
            .filter { headsInward(state, it) }
            .randomOrNull(random)

        if (inwardAngle == null) {
            finishLine(state)
            return
        }
        segmentsInLine++
        beginSegment(state, config, inwardAngle, randomRunLength(state))
    }

    private fun randomGridAngle(): Float = random.nextInt(GRID_DIRECTIONS) * GRID_STEP

    private fun gridStepOf(angle: Float): Int =
        ((angle / GRID_STEP).roundToInt() + GRID_DIRECTIONS) % GRID_DIRECTIONS

    /** Turns onto a neighbouring grid direction, never straight on and never back. */
    private fun turnFrom(angle: Float): Float {
        val turn = TURN_CHOICES.random(random)
        return ((gridStepOf(angle) + turn) % GRID_DIRECTIONS) * GRID_STEP
    }

    private fun randomRunLength(state: DrawState): Float {
        val reference = min(state.width, state.height)
        return reference * (MIN_RUN_FRACTION + random.nextFloat() * (MAX_RUN_FRACTION - MIN_RUN_FRACTION))
    }

    private companion object {
        const val GRID_DIRECTIONS = 8
        const val GRID_STEP = (2.0 * PI / GRID_DIRECTIONS).toFloat()

        /**
         * Grid steps a turn may take. 45 and 90 degree turns dominate; the sharper
         * 135 degree turns show up occasionally to keep the shapes from looking
         * mechanical. 0 (straight on) and 4 (doubling back) are deliberately absent.
         */
        val TURN_CHOICES = intArrayOf(1, 1, 1, 2, 2, 6, 6, 7, 7, 7, 3, 5)

        const val MIN_RUN_FRACTION = 0.10f
        const val MAX_RUN_FRACTION = 0.36f

        /** Keeps one line from wandering the whole screen before another starts. */
        const val MAX_SEGMENTS_PER_LINE = 14
    }
}
