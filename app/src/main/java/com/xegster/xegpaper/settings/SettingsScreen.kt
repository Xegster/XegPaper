package com.xegster.xegpaper.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xegster.xegpaper.engine.PatternEngine
import com.xegster.xegpaper.engine.PatternType
import com.xegster.xegpaper.engine.WallpaperConfig
import kotlin.math.roundToInt

/** Preset swatches, so the settings screen does not need a full colour picker yet. */
private val LineColorChoices = listOf(
    0xFF4CC9F0, 0xFFF72585, 0xFF7209B7, 0xFF52B788,
    0xFFFFB703, 0xFFF8F9FA, 0xFFE63946, 0xFF4361EE,
).map { it.toInt() }

private val BackgroundColorChoices = listOf(
    0xFF0B0F1A, 0xFF000000, 0xFF10162A, 0xFF071A12,
    0xFF1A0B1E, 0xFF1C1C1E, 0xFFF8F9FA,
).map { it.toInt() }

@Composable
fun SettingsScreen(
    config: WallpaperConfig,
    isWallpaperActive: Boolean,
    onPatternChange: (PatternType) -> Unit,
    onLineColorChange: (Int) -> Unit,
    onBackgroundColorChange: (Int) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onLineWidthChange: (Float) -> Unit,
    onMaxSegmentsChange: (Int) -> Unit,
    onSetWallpaper: () -> Unit,
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // The preview stays put while the controls scroll, so the effect of a slider is
        // visible while it is being dragged.
        Text(
            text = "XegPaper",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        )

        PatternPreview(
            config = config,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(240.dp)
                .clip(RoundedCornerShape(20.dp)),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Button(
                onClick = onSetWallpaper,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isWallpaperActive) "Reopen wallpaper picker" else "Set as wallpaper")
            }

            if (isWallpaperActive) {
                Text(
                    text = "XegPaper is your live wallpaper. Changes below apply straight away.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingSection(title = "Pattern") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PatternType.entries.forEach { pattern ->
                        FilterChip(
                            selected = config.patternType == pattern,
                            onClick = { onPatternChange(pattern) },
                            label = { Text(pattern.displayName) },
                        )
                    }
                }
                Text(
                    text = config.patternType.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingSection(title = "Line colour") {
                ColorSwatchRow(
                    colors = LineColorChoices,
                    selected = config.lineColor,
                    onSelect = onLineColorChange,
                )
            }

            SettingSection(title = "Background colour") {
                ColorSwatchRow(
                    colors = BackgroundColorChoices,
                    selected = config.backgroundColor,
                    onSelect = onBackgroundColorChange,
                )
            }

            SettingSection(title = "Speed", value = "${config.speedDpPerSecond.roundToInt()} dp/s") {
                Slider(
                    value = config.speedDpPerSecond,
                    onValueChange = onSpeedChange,
                    valueRange = WallpaperConfig.MIN_SPEED_DP_PER_SECOND..WallpaperConfig.MAX_SPEED_DP_PER_SECOND,
                )
            }

            SettingSection(title = "Line thickness", value = formatDp(config.lineWidthDp)) {
                Slider(
                    value = config.lineWidthDp,
                    onValueChange = onLineWidthChange,
                    valueRange = WallpaperConfig.MIN_LINE_WIDTH_DP..WallpaperConfig.MAX_LINE_WIDTH_DP,
                )
            }

            SettingSection(title = "Density before restart", value = "${config.maxSegments} lines") {
                Slider(
                    value = config.maxSegments.toFloat(),
                    onValueChange = { onMaxSegmentsChange(it.roundToInt()) },
                    valueRange = WallpaperConfig.MIN_SEGMENTS.toFloat()..WallpaperConfig.MAX_SEGMENTS.toFloat(),
                )
            }

            TextButton(
                onClick = onResetDefaults,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Reset to defaults")
            }
        }
    }
}

/**
 * A live preview driven by the same [PatternEngine] the wallpaper service runs, so
 * what shows here is exactly what lands on the home screen.
 */
@Composable
fun PatternPreview(config: WallpaperConfig, modifier: Modifier = Modifier) {
    val engine = remember { PatternEngine(config) }
    val density = LocalDensity.current.density
    var frameNanos by remember { mutableLongStateOf(0L) }

    LaunchedEffect(config) { engine.config = config }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { now ->
                engine.advance(now)
                frameNanos = now
            }
        }
    }

    Canvas(modifier) {
        // Reading the frame stamp inside the draw lambda repaints every frame without
        // recomposing anything.
        frameNanos
        engine.setSurface(size.width, size.height, density)
        drawIntoCanvas { canvas -> engine.draw(canvas.nativeCanvas) }
    }
}

@Composable
private fun SettingSection(
    title: String,
    value: String? = null,
    content: @Composable () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (value != null) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun ColorSwatchRow(
    colors: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        colors.forEach { color ->
            val isSelected = color == selected
            // The ring sits outside the fill with a gap, so it stays visible even when
            // the swatch happens to be the same colour as the ring.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = CircleShape,
                    )
                    .clickable { onSelect(color) }
                    .padding(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(Color(color)),
                )
            }
        }
    }
}

private fun formatDp(value: Float): String {
    val tenths = (value * 10f).roundToInt()
    return "${tenths / 10}.${tenths % 10} dp"
}

private val PatternType.displayName: String
    get() = when (this) {
        PatternType.ANGULAR -> "Angular walk"
        PatternType.RADIAL -> "Radial burst"
        PatternType.FLOWING -> "Flowing curves"
        PatternType.BRANCHING -> "Branching"
        PatternType.AGGREGATION -> "Aggregation"
        PatternType.CELLULAR -> "Cellular"
        PatternType.BLOOM -> "Bloom"
        PatternType.SCYTHE -> "Scythe"
        PatternType.SCYTHE_BUBBLE -> "Scythe bubble"
        PatternType.MULTI_SCYTHE -> "Multi-scythe"
    }

private val PatternType.description: String
    get() = when (this) {
        PatternType.ANGULAR ->
            "One line walks the screen on 45-degree turns and stops the moment it crosses itself or an earlier line."

        PatternType.RADIAL ->
            "Spokes fire one at a time from a moving anchor, each clipped by the lines already drawn."

        PatternType.FLOWING ->
            "Curves swell into round bubbles and pinch into pointed blades, seeded from the canvas edge and the lines already drawn, each stopping exactly where it meets existing geometry."

        PatternType.BRANCHING ->
            "A line grows from the edge and forks into two as it travels, each branch curving gently until it meets other geometry."

        PatternType.AGGREGATION ->
            "Wandering walkers drift out from the edge on a random walk until they bump into existing lines and stick, like coral or frost forming."

        PatternType.CELLULAR ->
            "Straight walls grow outward between scattered points, partitioning the canvas into faceted, crystal-like cells."

        PatternType.BLOOM ->
            "Flowing curves biased heavily toward round bubbles, forking as they grow like a vine blooming and dividing."

        PatternType.SCYTHE ->
            "Crescent moons, half-moons, claws and scythe blades, each drawn as two arcs meeting at sharp points and stopping where they meet existing lines."

        PatternType.SCYTHE_BUBBLE ->
            "A curve of round bubbles that occasionally sharpens into a scythe blade for a stretch, then keeps bubbling on from where the blade ended."

        PatternType.MULTI_SCYTHE ->
            "Scythes chained base to base into a zigzag of alternating blades, curving as it grows."
    }
