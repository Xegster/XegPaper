package com.xegster.xegpaper.engine

/**
 * One straight piece of a drawn line.
 *
 * [endX]/[endY] are mutable so the segment currently being drawn can be extended in
 * place every frame instead of allocating a new object per frame. [color] is per
 * segment (rather than read from the config at paint time) so a pattern can vary
 * colour along a line later on; [PatternEngine] rewrites it when the user picks a new
 * line colour, so the change shows up without wiping the drawing.
 */
class LineSegment(
    val startX: Float,
    val startY: Float,
    var endX: Float,
    var endY: Float,
    var color: Int,
)
