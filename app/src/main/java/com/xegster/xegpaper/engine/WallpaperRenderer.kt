package com.xegster.xegpaper.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff

/**
 * Paints a [DrawState] onto a [Canvas]. Deliberately knows nothing about where the
 * canvas came from, so the wallpaper service and the in-app Compose preview render
 * identically.
 */
class WallpaperRenderer {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun draw(canvas: Canvas, state: DrawState, config: WallpaperConfig) {
        // SRC rather than the default SRC_OVER: the surface holds the previous frame,
        // and a translucent background colour would otherwise let it show through.
        canvas.drawColor(config.backgroundColor, PorterDuff.Mode.SRC)

        val segments = state.segments
        if (segments.isEmpty()) return

        linePaint.strokeWidth = (config.lineWidthDp * state.density).coerceAtLeast(1f)
        val fade = state.alpha.coerceIn(0f, 1f)

        for (index in segments.indices) {
            val segment = segments[index]
            linePaint.color = segment.color
            if (fade < 1f) {
                linePaint.alpha = (Color.alpha(segment.color) * fade).toInt()
            }
            canvas.drawLine(segment.startX, segment.startY, segment.endX, segment.endY, linePaint)
        }
    }
}
