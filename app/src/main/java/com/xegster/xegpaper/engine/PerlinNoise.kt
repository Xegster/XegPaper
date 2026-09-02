package com.xegster.xegpaper.engine

import kotlin.math.floor
import kotlin.random.Random

/**
 * Classic 1D Perlin (gradient) noise, seeded from [random] so each growing line gets
 * its own independent, smoothly-varying steering signal. Output is in roughly
 * `[-1, 1]`.
 */
class PerlinNoise(random: Random) {

    private val permutation = IntArray(PERMUTATION_SIZE * 2)

    init {
        val base = IntArray(PERMUTATION_SIZE) { it }
        for (i in PERMUTATION_SIZE - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val swap = base[i]
            base[i] = base[j]
            base[j] = swap
        }
        for (i in 0 until PERMUTATION_SIZE) {
            permutation[i] = base[i]
            permutation[i + PERMUTATION_SIZE] = base[i]
        }
    }

    /** Smooth noise value at [x]. */
    fun sample(x: Float): Float {
        val xi = floor(x).toInt() and (PERMUTATION_SIZE - 1)
        val xf = x - floor(x)
        val u = fade(xf)
        val a = permutation[xi]
        val b = permutation[xi + 1]
        return lerp(grad(a, xf), grad(b, xf - 1f), u)
    }

    private fun fade(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

    private fun lerp(a: Float, b: Float, t: Float): Float = a + t * (b - a)

    private fun grad(hash: Int, x: Float): Float = if (hash and 1 == 0) x else -x

    private companion object {
        const val PERMUTATION_SIZE = 256
    }
}
