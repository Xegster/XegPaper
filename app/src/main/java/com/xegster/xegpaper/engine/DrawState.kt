package com.xegster.xegpaper.engine

/**
 * Everything that has been drawn on the wallpaper surface so far, plus the surface
 * metrics the pattern generators need. Shared by the wallpaper service and the
 * in-app Compose preview.
 */
class DrawState {

    /** Surface width in pixels. Zero until the host reports a size. */
    var width: Float = 0f
        private set

    /** Surface height in pixels. Zero until the host reports a size. */
    var height: Float = 0f
        private set

    /** Screen density, so patterns and stroke widths can work in dp. */
    var density: Float = 1f

    /** Finished segments plus the one currently growing (always the last entry). */
    val segments: MutableList<LineSegment> = ArrayList()

    /** Global opacity, driven by the fade-out that runs before the canvas restarts. */
    var alpha: Float = 1f

    /** True while the composition is fading out ahead of a restart. */
    var fading: Boolean = false

    val hasSurface: Boolean
        get() = width > 0f && height > 0f

    /** Returns true when the size actually changed, so callers can restart the pattern. */
    fun resize(newWidth: Float, newHeight: Float): Boolean {
        if (newWidth == width && newHeight == height) return false
        width = newWidth
        height = newHeight
        return true
    }

    fun clear() {
        segments.clear()
        alpha = 1f
        fading = false
    }
}
