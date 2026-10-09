package com.xegster.xegpaper.engine

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Lightning/coral-style growth: one line starts on the outer perimeter (canvas edges
 * plus lines already drawn, via [PerimeterTracker], same as [FlowingLinePattern]) and,
 * as it travels, occasionally forks into two independent tips that keep growing
 * side by side. Each tip carries a small constant curvature fixed at birth, so
 * individual branches read as gentle arcs rather than dead-straight rays; the
 * branching itself — not steering noise — is what gives the pattern its organic,
 * nested structure.
 *
 * Every tip is clipped by [SegmentStepper] exactly like every other pattern here: it
 * stops the instant it crosses existing geometry (including its siblings) or the
 * canvas edge.
 */
class BranchingLinePattern(private val random: Random = Random.Default) : PatternGenerator {

    override val type: PatternType = PatternType.BRANCHING

    private val perimeter = PerimeterTracker(random)
    private val tips = ArrayList<Tip>()

    override fun reset(state: DrawState, config: WallpaperConfig) {
        state.clear()
        tips.clear()
    }

    /** Once every tip has terminated on its own, there is nothing left growing — see
     *  the interface doc on why this matters for the fade-and-restart cycle. */
    override fun isSettled(): Boolean = tips.isEmpty()

    override fun update(state: DrawState, config: WallpaperConfig, deltaSeconds: Float) {
        if (!state.hasSurface || deltaSeconds <= 0f) return

        ensureSeed(state, config)
        if (tips.isEmpty()) return

        val budget = config.speedDpPerSecond * state.density * deltaSeconds
        val perTip = budget / tips.size
        for (index in tips.indices.reversed()) {
            if (stepTip(state, config, tips[index], perTip)) tips.removeAt(index)
        }
    }

    private fun ensureSeed(state: DrawState, config: WallpaperConfig) {
        if (tips.isNotEmpty() || state.segments.size >= config.maxSegments) return
        val spawn = perimeter.randomSpawnPoint(state)
        tips.add(Tip(spawn[0], spawn[1], spawn[2], randomCurvature()))
    }

    /** Advances [tip] by up to [budgetPx], one short chord at a time so branching can
     *  be evaluated between chords. Returns true once the tip has terminated. */
    private fun stepTip(state: DrawState, config: WallpaperConfig, tip: Tip, budgetPx: Float): Boolean {
        var remaining = budgetPx
        while (remaining > 0f) {
            val chordPx = min(remaining, CHORD_DP * state.density)
            if (chordPx <= 0f) return false

            val chordDp = chordPx / state.density
            tip.angle += tip.curvaturePerDp * chordDp
            val nextX = tip.x + cos(tip.angle) * chordPx
            val nextY = tip.y + sin(tip.angle) * chordPx

            val result = SegmentStepper.step(state, tip.x, tip.y, nextX, nextY)
            state.segments.add(LineSegment(tip.x, tip.y, result.endX, result.endY, config.lineColor))

            val consumedPx = chordPx * result.fraction
            remaining -= consumedPx
            tip.x = result.endX
            tip.y = result.endY
            tip.distanceSinceBranchDp += consumedPx / state.density

            if (result.terminated) return true
            maybeBranch(state, tip)
        }
        return false
    }

    private fun maybeBranch(state: DrawState, tip: Tip) {
        if (tips.size >= MAX_TIPS) return
        if (tip.distanceSinceBranchDp < MIN_BRANCH_SPACING_DP) return
        if (random.nextFloat() >= BRANCH_PROBABILITY_PER_CHORD) return

        val side = if (random.nextBoolean()) 1f else -1f
        val angleOffset = side * (BRANCH_ANGLE_MIN_DEG + random.nextFloat() * (BRANCH_ANGLE_MAX_DEG - BRANCH_ANGLE_MIN_DEG))
        tips.add(Tip(tip.x, tip.y, tip.angle + Math.toRadians(angleOffset.toDouble()).toFloat(), randomCurvature()))
        tip.distanceSinceBranchDp = 0f
    }

    private fun randomCurvature(): Float {
        val magnitude = CURVATURE_MIN_PER_DP + random.nextFloat() * (CURVATURE_MAX_PER_DP - CURVATURE_MIN_PER_DP)
        return if (random.nextBoolean()) magnitude else -magnitude
    }

    private fun min(a: Float, b: Float): Float = if (a < b) a else b

    private class Tip(
        var x: Float,
        var y: Float,
        var angle: Float,
        val curvaturePerDp: Float,
        var distanceSinceBranchDp: Float = 0f,
    )

    private companion object {
        /** Length of each straight chord approximating the curve, in dp. */
        const val CHORD_DP = 8f

        /** Caps concurrent tips so a long-lived tree cannot cost unbounded work per frame. */
        const val MAX_TIPS = 24

        /** A tip must travel at least this far since its last fork before it can fork again. */
        const val MIN_BRANCH_SPACING_DP = 50f

        /** Rolled once per chord once [MIN_BRANCH_SPACING_DP] has been covered. */
        const val BRANCH_PROBABILITY_PER_CHORD = 0.10f

        const val BRANCH_ANGLE_MIN_DEG = 20f
        const val BRANCH_ANGLE_MAX_DEG = 55f

        /** Constant per-tip curvature range, in radians per dp, that bows each branch into
         *  a gentle arc instead of a dead-straight ray. */
        const val CURVATURE_MIN_PER_DP = 0.004f
        const val CURVATURE_MAX_PER_DP = 0.015f
    }
}
