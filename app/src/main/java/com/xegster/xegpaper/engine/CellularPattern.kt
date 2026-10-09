package com.xegster.xegpaper.engine

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Crystalline cell-wall growth, loosely modelled on how a Voronoi diagram is built
 * incrementally: every new "site" is dropped as far as possible from the sites
 * already placed (best-candidate sampling, so sites spread out rather than
 * clustering), then a wall grows outward in both directions from the midpoint
 * between it and its nearest existing neighbour, perpendicular to the line joining
 * them — the perpendicular bisector a real Voronoi cell boundary would follow.
 *
 * This is a genuine departure from every other pattern here: walls are straight (only
 * a light Perlin wobble rides on top for texture, exactly as documented on
 * [LineGrower] — it never drives the direction), so the result reads as faceted,
 * nested cells rather than an organic curve. Each wall tip is still clipped by
 * [SegmentStepper] the instant it meets an earlier wall or the canvas edge, which is
 * what makes the cells close up into a partition instead of overlapping.
 */
class CellularPattern(private val random: Random = Random.Default) : PatternGenerator {

    override val type: PatternType = PatternType.CELLULAR

    private val sites = ArrayList<FloatArray>()
    private val tips = ArrayList<Tip>()

    override fun reset(state: DrawState, config: WallpaperConfig) {
        state.clear()
        sites.clear()
        tips.clear()
    }

    override fun isSettled(): Boolean = tips.isEmpty()

    override fun update(state: DrawState, config: WallpaperConfig, deltaSeconds: Float) {
        if (!state.hasSurface || deltaSeconds <= 0f) return

        ensureGrowth(state, config)
        if (tips.isEmpty()) return

        val budget = config.speedDpPerSecond * state.density * deltaSeconds
        val perTip = budget / tips.size
        for (index in tips.indices.reversed()) {
            if (stepTip(state, config, tips[index], perTip)) tips.removeAt(index)
        }
    }

    private fun ensureGrowth(state: DrawState, config: WallpaperConfig) {
        while (tips.size < MAX_CONCURRENT_EVENTS * 2 && state.segments.size < config.maxSegments) {
            if (sites.isEmpty()) {
                sites.add(pickSite(state))
                continue
            }
            val candidate = pickSite(state)
            val neighbor = nearestSite(candidate)
            spawnWall(candidate, neighbor)
            sites.add(candidate)
        }
    }

    /** Best-candidate sampling: try a handful of random points and keep the one
     *  farthest from every existing site, so sites spread out across the canvas
     *  instead of clustering together. */
    private fun pickSite(state: DrawState): FloatArray {
        val margin = SITE_MARGIN_DP * state.density
        val usableWidth = (state.width - margin * 2f).coerceAtLeast(1f)
        val usableHeight = (state.height - margin * 2f).coerceAtLeast(1f)

        var bestX = margin + random.nextFloat() * usableWidth
        var bestY = margin + random.nextFloat() * usableHeight
        var bestDistance = -1f
        repeat(SITE_CANDIDATE_ATTEMPTS) {
            val x = margin + random.nextFloat() * usableWidth
            val y = margin + random.nextFloat() * usableHeight
            var nearest = Float.MAX_VALUE
            for (site in sites) nearest = min(nearest, hypot(site[0] - x, site[1] - y))
            if (nearest > bestDistance) {
                bestDistance = nearest
                bestX = x
                bestY = y
            }
        }
        return floatArrayOf(bestX, bestY)
    }

    private fun nearestSite(candidate: FloatArray): FloatArray {
        var best = sites[0]
        var bestDistance = hypot(best[0] - candidate[0], best[1] - candidate[1])
        for (index in 1 until sites.size) {
            val site = sites[index]
            val distance = hypot(site[0] - candidate[0], site[1] - candidate[1])
            if (distance < bestDistance) {
                bestDistance = distance
                best = site
            }
        }
        return best
    }

    private fun spawnWall(site: FloatArray, neighbor: FloatArray) {
        val midX = (site[0] + neighbor[0]) / 2f
        val midY = (site[1] + neighbor[1]) / 2f
        val baseAngle = atan2(site[1] - neighbor[1], site[0] - neighbor[0])
        tips.add(Tip(midX, midY, baseAngle + HALF_PI, PerlinNoise(random)))
        tips.add(Tip(midX, midY, baseAngle - HALF_PI, PerlinNoise(random)))
    }

    /** Advances [tip] by up to [budgetPx]. Returns true once it has terminated. */
    private fun stepTip(state: DrawState, config: WallpaperConfig, tip: Tip, budgetPx: Float): Boolean {
        var remaining = budgetPx
        while (remaining > 0f) {
            val chordPx = min(remaining, CHORD_DP * state.density)
            if (chordPx <= 0f) return false

            val wobble = tip.noise.sample(tip.traveledDp * NOISE_FREQUENCY) * WOBBLE_AMPLITUDE_RADIANS
            val angle = tip.baseAngle + wobble
            val nextX = tip.x + cos(angle) * chordPx
            val nextY = tip.y + sin(angle) * chordPx

            val result = SegmentStepper.step(state, tip.x, tip.y, nextX, nextY)
            state.segments.add(LineSegment(tip.x, tip.y, result.endX, result.endY, config.lineColor))

            val consumedPx = chordPx * result.fraction
            remaining -= consumedPx
            tip.x = result.endX
            tip.y = result.endY
            tip.traveledDp += consumedPx / state.density

            if (result.terminated) return true
        }
        return false
    }

    private class Tip(
        var x: Float,
        var y: Float,
        val baseAngle: Float,
        val noise: PerlinNoise,
        var traveledDp: Float = 0f,
    )

    private companion object {
        const val CHORD_DP = 8f

        const val SITE_MARGIN_DP = 14f
        const val SITE_CANDIDATE_ATTEMPTS = 12

        /** New site-plus-wall events allowed in flight at once; each spawns two tips. */
        const val MAX_CONCURRENT_EVENTS = 2

        const val NOISE_FREQUENCY = 0.02f

        /** Light surface wobble only — the wall's direction is the bisector, not this. */
        val WOBBLE_AMPLITUDE_RADIANS = Math.toRadians(4.0).toFloat()

        val HALF_PI = (PI / 2.0).toFloat()
    }
}
