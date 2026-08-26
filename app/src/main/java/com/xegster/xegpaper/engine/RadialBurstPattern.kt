package com.xegster.xegpaper.engine

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Spokes fired one at a time from an anchor point, sweeping around it. Each spoke
 * runs outward until it crosses an existing line or leaves the screen, so every burst
 * is clipped by the ones drawn before it. When the burst is complete a new anchor is
 * chosen somewhere with room around it.
 */
class RadialBurstPattern(random: Random = Random.Default) : GrowingLinePattern(random) {

    override val type: PatternType = PatternType.RADIAL

    private var anchorX = 0f
    private var anchorY = 0f
    private var baseAngle = 0f
    private var spokeCount = MIN_SPOKES
    private var spokesDrawn = 0

    override fun onReset(state: DrawState, config: WallpaperConfig) {
        spokesDrawn = spokeCount
    }

    override fun startNextLine(state: DrawState, config: WallpaperConfig): Boolean {
        if (spokesDrawn >= spokeCount) newBurst(state)

        val angle = baseAngle + spokesDrawn * (TWO_PI / spokeCount)
        spokesDrawn++
        // Spokes share the anchor exactly, so the intersection test skips its siblings
        // at the hub and only stops them where they meet earlier bursts.
        beginLine(state, config, anchorX, anchorY, angle, maxReach(state))
        return true
    }

    private fun newBurst(state: DrawState) {
        val point = pickOpenPoint(state, ANCHOR_CLEARANCE_DP)
        anchorX = point[0]
        anchorY = point[1]
        baseAngle = random.nextFloat() * TWO_PI
        spokeCount = random.nextInt(MIN_SPOKES, MAX_SPOKES + 1)
        spokesDrawn = 0
    }

    /** Long enough that a spoke always ends on a line or an edge, never mid-air. */
    private fun maxReach(state: DrawState): Float = hypot(state.width, state.height)

    private companion object {
        val TWO_PI = (2.0 * PI).toFloat()

        const val MIN_SPOKES = 5
        const val MAX_SPOKES = 9

        /** Anchors need more elbow room than a plain start point. */
        const val ANCHOR_CLEARANCE_DP = 40f
    }
}
