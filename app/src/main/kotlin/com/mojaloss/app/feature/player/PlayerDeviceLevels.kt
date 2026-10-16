package com.mojaloss.app.feature.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import kotlin.math.roundToInt

/** Window-only brightness and media-stream volume; called at gesture start/release, never per frame. */
internal class PlayerDeviceLevels(private val context: Context, private val activity: Activity?) {
    private val originalBrightness = activity?.window?.attributes?.screenBrightness
    private val audio = context.getSystemService(AudioManager::class.java)

    fun brightness(): Float? {
        val window = activity?.window ?: return null
        val override = window.attributes.screenBrightness
        if (override >= 0f) return override.coerceIn(.05f, 1f)
        return try {
            (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f)
                .coerceIn(.05f, 1f)
        } catch (_: Settings.SettingNotFoundException) { null }
        catch (_: SecurityException) { null }
    }

    fun volume(): Float? {
        val manager = audio ?: return null
        if (manager.isVolumeFixed) return null
        val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (maximum > 0) manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maximum else null
    }

    fun setBrightness(value: Float): Float? {
        if (!value.isFinite()) return brightness()
        activity?.window?.let { window ->
            val attributes = window.attributes
            attributes.screenBrightness = value.coerceIn(.05f, 1f)
            window.attributes = attributes
        }
        return brightness()
    }

    fun setVolume(value: Float): Float? {
        val manager = audio ?: return null
        if (!value.isFinite() || manager.isVolumeFixed) return volume()
        val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maximum <= 0) return null
        try {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC,
                (value.coerceIn(0f, 1f) * maximum).roundToInt(), 0)
        } catch (_: SecurityException) {
            // Policy/DND can refuse a change: the caller receives the real readback.
        }
        return volume()
    }

    fun restoreBrightness() {
        val saved = originalBrightness ?: return
        activity?.window?.let { window ->
            val attributes = window.attributes
            attributes.screenBrightness = saved
            window.attributes = attributes
        }
    }
}
