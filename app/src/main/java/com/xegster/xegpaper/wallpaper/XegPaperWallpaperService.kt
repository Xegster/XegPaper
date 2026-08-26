package com.xegster.xegpaper.wallpaper

import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.xegster.xegpaper.engine.PatternEngine
import com.xegster.xegpaper.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The live wallpaper itself. Registered in the manifest with
 * `android.permission.BIND_WALLPAPER` and pointed at `res/xml/wallpaper_info.xml`,
 * which is what makes XegPaper show up in the system wallpaper picker.
 *
 * All of the drawing lives in [PatternEngine]; this class only owns the surface, the
 * frame loop, and the settings subscription.
 */
class XegPaperWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = XegPaperEngine()

    private inner class XegPaperEngine : Engine() {

        private val handler = Handler(Looper.getMainLooper())
        private val patternEngine = PatternEngine()
        private val drawRunnable = Runnable { drawFrame() }
        private var scope: CoroutineScope? = null
        private var running = false

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            // The settings activity writes to the same DataStore in the same process,
            // so edits reach a running wallpaper without a bridge or a restart.
            val repository = SettingsRepository(this@XegPaperWallpaperService)
            val coroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
            scope = coroutineScope
            coroutineScope.launch {
                repository.config.collect { patternEngine.config = it }
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            patternEngine.setSurface(
                width.toFloat(),
                height.toFloat(),
                resources.displayMetrics.density,
            )
            drawFrame()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            running = visible
            if (visible) {
                // Do not let the time spent hidden count as one enormous frame.
                patternEngine.pauseClock()
                drawFrame()
            } else {
                handler.removeCallbacks(drawRunnable)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            running = false
            handler.removeCallbacks(drawRunnable)
        }

        override fun onDestroy() {
            running = false
            handler.removeCallbacks(drawRunnable)
            scope?.cancel()
            scope = null
            super.onDestroy()
        }

        /**
         * TODO(touch): seed a new line at the touch point once the reference patterns
         * land. Enabling it also needs `setTouchEventsEnabled(true)` in [onCreate];
         * it stays off for now so the wallpaper is not woken by every home-screen tap.
         */
        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)
        }

        private fun drawFrame() {
            handler.removeCallbacks(drawRunnable)
            val frameStart = SystemClock.uptimeMillis()

            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    patternEngine.advance(System.nanoTime())
                    patternEngine.draw(canvas)
                }
            } finally {
                if (canvas != null) {
                    // The surface can go away mid-frame while the wallpaper is being changed.
                    runCatching { holder.unlockCanvasAndPost(canvas) }
                }
            }

            if (running) {
                val elapsed = SystemClock.uptimeMillis() - frameStart
                handler.postDelayed(drawRunnable, (FRAME_INTERVAL_MILLIS - elapsed).coerceAtLeast(0L))
            }
        }
    }

    private companion object {
        /** ~60fps. The loop is torn down whenever the wallpaper is not visible. */
        const val FRAME_INTERVAL_MILLIS = 16L
    }
}
