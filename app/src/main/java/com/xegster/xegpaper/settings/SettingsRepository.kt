package com.xegster.xegpaper.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xegster.xegpaper.engine.PatternType
import com.xegster.xegpaper.engine.WallpaperConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "xegpaper_settings",
)

/**
 * Persists the wallpaper settings and hands them out as a [Flow].
 *
 * The settings activity and the wallpaper service run in the same process, so both
 * read this one DataStore and the running wallpaper picks up edits live — no IPC or
 * broadcast needed.
 */
class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    val config: Flow<WallpaperConfig> = dataStore.data
        .catch { throwable ->
            // A corrupt or unreadable file should show the defaults, not crash the wallpaper.
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences -> preferences.toConfig() }

    suspend fun setPatternType(patternType: PatternType) = edit {
        it[Keys.PATTERN] = patternType.name
    }

    suspend fun setBackgroundColor(color: Int) = edit { it[Keys.BACKGROUND_COLOR] = color }

    suspend fun setLineColor(color: Int) = edit { it[Keys.LINE_COLOR] = color }

    suspend fun setSpeed(dpPerSecond: Float) = edit {
        it[Keys.SPEED] = dpPerSecond.coerceIn(
            WallpaperConfig.MIN_SPEED_DP_PER_SECOND,
            WallpaperConfig.MAX_SPEED_DP_PER_SECOND,
        )
    }

    suspend fun setLineWidth(dp: Float) = edit {
        it[Keys.LINE_WIDTH] = dp.coerceIn(
            WallpaperConfig.MIN_LINE_WIDTH_DP,
            WallpaperConfig.MAX_LINE_WIDTH_DP,
        )
    }

    suspend fun setMaxSegments(segments: Int) = edit {
        it[Keys.MAX_SEGMENTS] = segments.coerceIn(
            WallpaperConfig.MIN_SEGMENTS,
            WallpaperConfig.MAX_SEGMENTS,
        )
    }

    suspend fun resetToDefaults() = edit { it.clear() }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private fun Preferences.toConfig(): WallpaperConfig {
        val defaults = WallpaperConfig.DEFAULT
        return WallpaperConfig(
            patternType = PatternType.fromNameOrDefault(this[Keys.PATTERN]),
            backgroundColor = this[Keys.BACKGROUND_COLOR] ?: defaults.backgroundColor,
            lineColor = this[Keys.LINE_COLOR] ?: defaults.lineColor,
            speedDpPerSecond = this[Keys.SPEED] ?: defaults.speedDpPerSecond,
            lineWidthDp = this[Keys.LINE_WIDTH] ?: defaults.lineWidthDp,
            maxSegments = this[Keys.MAX_SEGMENTS] ?: defaults.maxSegments,
        )
    }

    private object Keys {
        val PATTERN = stringPreferencesKey("pattern_type")
        val BACKGROUND_COLOR = intPreferencesKey("background_color")
        val LINE_COLOR = intPreferencesKey("line_color")
        val SPEED = floatPreferencesKey("speed_dp_per_second")
        val LINE_WIDTH = floatPreferencesKey("line_width_dp")
        val MAX_SEGMENTS = intPreferencesKey("max_segments")
    }
}
