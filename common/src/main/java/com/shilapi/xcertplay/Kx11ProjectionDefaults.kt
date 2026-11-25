package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.network.CarHotspotSettings

/**
 * One-time safe default migration for KX11_A2: disables all HUD, three-finger fly-to-HUD,
 * ADB cluster activity, and secondary-display projection toggles on install/upgrade so the
 * vehicle's stock AR-HUD is never obscured unless the driver explicitly enables them.
 */
internal object Kx11ProjectionDefaults {
    private const val PREFS_NAME = "diplay_kx11_migrations"
    private const val KEY_HUD_RESET_V2 = "kx11_hud_reset_v2"

    fun applyOnceIfNeeded(
        context: Context,
        model: String = Build.MODEL ?: "",
    ): Boolean {
        if (!CarHotspotSettings.isKx11A2(model)) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_HUD_RESET_V2, false)) return false
        AirPlayPersistence.saveGeelyHudEnabled(context, false)
        VehicleMapSettings.setEnabled(context, false)
        AirPlayPersistence.saveClusterMapEnabled(context, false)
        GeelyHudProjection.setThreeFingerEnabled(context, false)
        AirPlayPersistence.saveAdbClusterEnabled(context, false)
        prefs.edit().putBoolean(KEY_HUD_RESET_V2, true).apply()
        return true
    }
}
