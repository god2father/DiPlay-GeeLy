package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.network.CarHotspotSettings

/**
 * Settings for KX11_A2 OEM DIM QNX integration (native AR-HUD/cluster navigation and cluster music).
 * Enabled by default on KX11_A2 because these use native QNX Binder APIs without drawing any
 * third-party overlay windows over the vehicle's stock HUD.
 */
object Kx11DimSettings {
    const val DIM_PACKAGE_NAME = "com.autolink.diminteraction"

    private const val PREFS_NAME = "diplay_kx11_dim"
    private const val KEY_NAVI_HUD_ENABLED = "kx11_dim_navi_hud_enabled"
    private const val KEY_CLUSTER_MEDIA_ENABLED = "kx11_dim_cluster_media_enabled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isSupported(model: String = Build.MODEL ?: ""): Boolean =
        CarHotspotSettings.isKx11A2(model)

    fun naviHudEnabled(context: Context, model: String = Build.MODEL ?: ""): Boolean {
        if (!isSupported(model)) return false
        return prefs(context).getBoolean(KEY_NAVI_HUD_ENABLED, true)
    }

    fun setNaviHudEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NAVI_HUD_ENABLED, enabled).apply()
    }

    fun clusterMediaEnabled(context: Context, model: String = Build.MODEL ?: ""): Boolean {
        if (!isSupported(model)) return false
        return prefs(context).getBoolean(KEY_CLUSTER_MEDIA_ENABLED, true)
    }

    fun setClusterMediaEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLUSTER_MEDIA_ENABLED, enabled).apply()
    }
}
