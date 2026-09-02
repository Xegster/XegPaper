package com.xegster.xegpaper.engine

import android.graphics.Canvas

/**
 * Owns a composition: the surface metrics, the active [PatternGenerator], the frame
 * clock, and the fade-and-restart cycle that runs once the canvas fills up — delayed
 * until [PatternGenerator.isSettled] so a still-growing line finishes on its own
 * (another line or the canvas edge) instead of being cut off mid-curve.
 *
 * Both the wallpaper service and the in-app preview drive one of these — they only
 * have to feed it a size, a frame timestamp, and a canvas.
 */
class PatternEngine(initialConfig: WallpaperConfig = WallpaperConfig.DEFAULT) {

    private val state = DrawState()
    private val renderer = WallpaperRenderer()
    private var generator: PatternGenerator = createPatternGenerator(initialConfig.patternType)
    private var lastFrameNanos = 0L
    private var restartPending = true

    var config: WallpaperConfig = initialConfig
        set(value) {
            val previous = field
            field = value
            if (previous.patternType != value.patternType) {
                generator = createPatternGenerator(value.patternType)
                restart()
            } else if (previous.lineColor != value.lineColor) {
                recolorSegments(value.lineColor)
            }
        }

    /** Reports the surface size; a real size change starts the drawing over. */
    fun setSurface(width: Float, height: Float, density: Float) {
        state.density = if (density > 0f) density else 1f
        if (state.resize(width, height)) restart()
    }

    /** Throws away the current drawing and starts a new one on the next frame. */
    fun restart() {
        restartPending = true
    }

    /**
     * Forgets the frame clock. Call it when the wallpaper is hidden so the first frame
     * after it comes back does not advance the drawing by the whole time it was away.
     */
    fun pauseClock() {
        lastFrameNanos = 0L
    }

    /** Advances the drawing to [frameTimeNanos]. */
    fun advance(frameTimeNanos: Long) {
        if (!state.hasSurface) return

        val previousFrameNanos = lastFrameNanos
        lastFrameNanos = frameTimeNanos
        if (previousFrameNanos == 0L) return

        val deltaSeconds = ((frameTimeNanos - previousFrameNanos) / NANOS_PER_SECOND)
            .toFloat()
            .coerceIn(0f, MAX_FRAME_SECONDS)
        if (deltaSeconds <= 0f) return

        if (restartPending) {
            generator.reset(state, config)
            restartPending = false
        }

        if (state.fading) {
            state.alpha -= deltaSeconds / FADE_SECONDS
            if (state.alpha <= 0f) generator.reset(state, config)
            return
        }

        if (state.segments.size >= config.maxSegments) {
            if (generator.isSettled()) {
                android.util.Log.d("XegPaperDebug", "CANVAS FULL + settled, fading at segments=${state.segments.size}, maxSegments=${config.maxSegments}")
                state.fading = true
                return
            } else {
                android.util.Log.d("XegPaperDebug", "CANVAS FULL but NOT settled, letting active growth finish, segments=${state.segments.size}")
            }
        }

        generator.update(state, config, deltaSeconds)
    }

    fun draw(canvas: Canvas) {
        renderer.draw(canvas, state, config)
    }

    private fun recolorSegments(color: Int) {
        val segments = state.segments
        for (index in segments.indices) segments[index].color = color
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0

        /** Caps the catch-up after a dropped or very long frame. */
        const val MAX_FRAME_SECONDS = 0.05f

        /** How long the finished composition takes to fade out before restarting. */
        const val FADE_SECONDS = 1.6f
    }
}
