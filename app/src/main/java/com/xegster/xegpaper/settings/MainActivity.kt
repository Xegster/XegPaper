package com.xegster.xegpaper.settings

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.xegster.xegpaper.wallpaper.XegPaperWallpaperService

/**
 * Launcher activity and the wallpaper's settings screen: it is also the
 * `settingsActivity` in `res/xml/wallpaper_info.xml`, reached through the alias
 * `WallpaperSettingsActivity`, so the system picker's "Settings" affordance lands here.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    /** Whether XegPaper is the live wallpaper right now; refreshed in [onResume]. */
    private var isWallpaperActive by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val config by viewModel.config.collectAsState()

            XegPaperTheme {
                Scaffold { innerPadding ->
                    SettingsScreen(
                        config = config,
                        isWallpaperActive = isWallpaperActive,
                        onPatternChange = viewModel::setPatternType,
                        onLineColorChange = viewModel::setLineColor,
                        onBackgroundColorChange = viewModel::setBackgroundColor,
                        onSpeedChange = viewModel::setSpeed,
                        onLineWidthChange = viewModel::setLineWidth,
                        onMaxSegmentsChange = viewModel::setMaxSegments,
                        onSetWallpaper = ::openWallpaperPicker,
                        onResetDefaults = viewModel::resetToDefaults,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isWallpaperActive = isXegPaperApplied()
    }

    private fun isXegPaperApplied(): Boolean {
        val applied = WallpaperManager.getInstance(this).wallpaperInfo ?: return false
        return applied.packageName == packageName &&
            applied.serviceName == XegPaperWallpaperService::class.java.name
    }

    /**
     * Opens the picker straight on XegPaper's preview. Some launchers and OEM skins do
     * not handle the targeted intent, so fall back to the plain live-wallpaper chooser.
     */
    private fun openWallpaperPicker() {
        val targeted = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, XegPaperWallpaperService::class.java),
        )
        if (startPicker(targeted)) return

        if (startPicker(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))) return

        Toast.makeText(
            this,
            "No wallpaper picker found. Set XegPaper from your launcher's wallpaper settings.",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun startPicker(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
