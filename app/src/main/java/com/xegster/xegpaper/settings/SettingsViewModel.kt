package com.xegster.xegpaper.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xegster.xegpaper.engine.PatternType
import com.xegster.xegpaper.engine.WallpaperConfig
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs [SettingsScreen]; every edit goes straight to [SettingsRepository]. */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)

    val config: StateFlow<WallpaperConfig> = repository.config.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = WallpaperConfig.DEFAULT,
    )

    fun setPatternType(patternType: PatternType) = update { setPatternType(patternType) }

    fun setBackgroundColor(color: Int) = update { setBackgroundColor(color) }

    fun setLineColor(color: Int) = update { setLineColor(color) }

    fun setSpeed(dpPerSecond: Float) = update { setSpeed(dpPerSecond) }

    fun setLineWidth(dp: Float) = update { setLineWidth(dp) }

    fun setMaxSegments(segments: Int) = update { setMaxSegments(segments) }

    fun resetToDefaults() = update { resetToDefaults() }

    private fun update(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { repository.block() }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
