package com.xegster.xegpaper.engine

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * A **scythe**: half of a crescent moon (see [ScytheLinePattern]'s KDoc for the full
 * shape's outer-arc/inner-arc construction), cut through the midpoint of each arc so
 * it keeps exactly one of the crescent's two horns as a sharp tip. Its other end is
 * left open as two "base" points — where the cut was made — instead of closing into a
 * second horn. See CLAUDE.md for this project's definition of the shape.
 *
 * A scythe's [Outline] runs base -> tip -> base: half the outer arc in to the tip,
 * then half the inner arc back out to its own base. Both halves are anchored to the
 * same chord and share the same sagitta/radius construction as the full crescent, so a
 * scythe reads as a clean bite out of that shape rather than a differently-curved
 * blade. [ScytheBubblePattern] sprouts these as thorns off a growing curve; joins them
 * base-to-base into chains.
 */
object ScytheGeometry {

    /** [points] is base -> tip -> base as interleaved x/y pixels; [tipIndex] is the
     *  point index (not the float offset) of the tip. */
    class Outline(val points: FloatArray, val tipIndex: Int) {
        val baseAX: Float get() = points[0]
        val baseAY: Float get() = points[1]
        val tipX: Float get() = points[tipIndex * 2]
        val tipY: Float get() = points[tipIndex * 2 + 1]
        val baseBX: Float get() = points[points.size - 2]
        val baseBY: Float get() = points[points.size - 1]
    }

    /**
     * Builds a scythe whose tip sits at ([tipX], [tipY]) pointing along [tipHeading] —
     * the direction the blade's point aims, away from its own body. [chordPx] is the
     * full crescent's horn-to-horn chord this scythe is half of, which sets its size;
     * [sideSign] picks which side it bulges toward, the same as
     * [ScytheLinePattern.buildOutline]'s parameter of the same name.
     */
    fun buildOutline(
        tipX: Float,
        tipY: Float,
        tipHeading: Float,
        chordPx: Float,
        sideSign: Float,
        outerHalfSweep: Float,
        innerHalfSweep: Float,
        stepPx: Float,
    ): Outline {
        val intoShapeHeading = tipHeading + PI_F
        val chordAngle = intoShapeHeading - sideSign * outerHalfSweep
        val dx = cos(chordAngle)
        val dy = sin(chordAngle)
        val nx = -sideSign * dy
        val ny = sideSign * dx
        val midX = tipX + dx * chordPx / 2f
        val midY = tipY + dy * chordPx / 2f

        val outerCount = maxOf(MIN_ARC_POINTS, ceil(halfArcLength(chordPx, outerHalfSweep) / stepPx).toInt())
        val innerCount = maxOf(MIN_ARC_POINTS, ceil(halfArcLength(chordPx, innerHalfSweep) / stepPx).toInt())
        val points = FloatArray((outerCount + 1 + innerCount) * 2)

        // Same along/bulge construction as the full crescent's [ScytheLinePattern],
        // over half its sweep: f in [0, 0.5] from the tip (f=0) to this arc's own
        // midpoint, the base (f=0.5).
        fun place(index: Int, f: Float, halfSweep: Float) {
            val along: Float
            val bulge: Float
            if (halfSweep < STRAIGHT_EPSILON) {
                along = -chordPx / 2f + chordPx * f
                bulge = 0f
            } else {
                val sagitta = (chordPx / 2f) * tan(halfSweep / 2f)
                val rho = (chordPx * chordPx / 4f + sagitta * sagitta) / (2f * sagitta)
                val phi = -halfSweep + 2f * halfSweep * f
                along = rho * sin(phi)
                bulge = rho * cos(phi) - (rho - sagitta)
            }
            points[index * 2] = midX + dx * along + nx * bulge
            points[index * 2 + 1] = midY + dy * along + ny * bulge
        }

        // Outer half, base to tip: f = 0.5 down to 0.
        for (i in 0..outerCount) place(i, 0.5f - 0.5f * i / outerCount, outerHalfSweep)
        // Inner half, tip back out to its own base: f = 0 up to 0.5.
        for (i in 1..innerCount) place(outerCount + i, 0.5f * i / innerCount, innerHalfSweep)

        // The tip is the one vertex shared by both halves; pin it exactly rather than
        // trusting two independent arc formulas to land on the same float twice.
        points[outerCount * 2] = tipX
        points[outerCount * 2 + 1] = tipY
        return Outline(points, outerCount)
    }

    /**
     * The tip position that makes [buildOutline]'s outer-arc base land exactly at
     * ([baseX], [baseY]) instead of wherever the formula happens to put it — lets
     * [MultiScythePattern] chain scythes so each new one's base sits exactly on the
     * previous one's, a shared vertex the same way the full crescent's two horns are,
     * rather than a gap bridged by a neck.
     *
     * Inverts the same construction [buildOutline] uses: the outer arc's base (its
     * f=0.5 point) sits at `mid + n * sagitta`, and `mid` sits `chordPx / 2` back
     * along `d` from the tip — so working backward from a desired base, `mid = base -
     * n * sagitta` and `tip = mid - d * chordPx / 2`.
     */
    fun tipForOuterBase(
        baseX: Float,
        baseY: Float,
        intoShapeHeading: Float,
        chordPx: Float,
        sideSign: Float,
        outerHalfSweep: Float,
    ): FloatArray {
        val chordAngle = intoShapeHeading - sideSign * outerHalfSweep
        val dx = cos(chordAngle)
        val dy = sin(chordAngle)
        val nx = -sideSign * dy
        val ny = sideSign * dx
        val sagitta = if (outerHalfSweep < STRAIGHT_EPSILON) 0f else (chordPx / 2f) * tan(outerHalfSweep / 2f)
        val midX = baseX - nx * sagitta
        val midY = baseY - ny * sagitta
        return floatArrayOf(midX - dx * chordPx / 2f, midY - dy * chordPx / 2f)
    }

    private fun halfArcLength(chordPx: Float, halfSweep: Float): Float =
        if (halfSweep < STRAIGHT_EPSILON) chordPx / 2f else chordPx * halfSweep / sin(halfSweep) / 2f

    private const val MIN_ARC_POINTS = 6
    private const val STRAIGHT_EPSILON = 1e-3f
    private val PI_F = PI.toFloat()
}
