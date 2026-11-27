package com.shilapi.xcertplay

import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.hud.Kx11DimSettings

/**
 * 2025 Geely Xingyue L (`KX11_A2`) 12.3" full-digital instrument cluster descriptor.
 *
 * Measured directly on `KX11_A2` (`ecarx-userdebug 11`):
 * - `Display 0` (`local:1`, 1920x720, 160dpi): center screen.
 * - `Display 1` (`local:132`, 800x480, 320dpi): auxiliary small display.
 * - `Display 2` (`local:5`, 1920x720, 160dpi): passenger screen (`PsdActivity`).
 * - `Display 3` (`local:130`, 1920x720, 320dpi): internal secondary/HUD channel.
 * - `Display 4` (`local:131`, 1920x720, 320dpi, `FLAG_PRESENTATION`): stock Amap full-bleed
 *   cluster map target.
 *
 * Unlike older twin-dial clusters, the 2025 KX11_A2 cluster overlays digital speed/telltales across
 * the top band (~18-20% height) and RPM/odometer/fuel/gear across the bottom band (~12-14% height),
 * while the map fills the full 1920x720 width edge-to-edge.
 */
internal object Kx11ClusterDisplay {
    const val STREAM_WIDTH = 1920
    const val STREAM_HEIGHT = 720
    const val CLUSTER_UNIQUE_ID = "local:131"
    const val EXPECTED_DENSITY_DPI = 320
    const val FALLBACK_DISPLAY_ID = 4

    /**
     * Asymmetric safe area matching the 2025 KX11_A2 digital cluster photo:
     * - Left / Right: 4% margin (76 px) — full-bleed modern digital screen with no side dials.
     * - Top: 20% margin (144 px) — clears centered large digital speedometer, clock and telltales.
     * - Bottom: 14% margin (100 px, bottom=620) — clears RPM bar, odometer, fuel range and gear.
     */
    const val DEFAULT_SAFE_LEFT = 76
    const val DEFAULT_SAFE_TOP = 144
    const val DEFAULT_SAFE_RIGHT = 1844
    const val DEFAULT_SAFE_BOTTOM = 620

    fun supported(model: String = Build.MODEL ?: ""): Boolean = Kx11DimSettings.isSupported(model)

    fun defaultSafeAreaRect(): SafeAreaRect = SafeAreaRect(
        left = DEFAULT_SAFE_LEFT,
        top = DEFAULT_SAFE_TOP,
        right = DEFAULT_SAFE_RIGHT,
        bottom = DEFAULT_SAFE_BOTTOM,
    )

    /**
     * Matches only the verified `local:131` cluster presentation display, preventing accidental
     * selection of the 160dpi passenger screen (`local:5`) or the `local:130` secondary/HUD panel
     * even if HWC enumeration swaps logical display IDs across boots.
     */
    fun matches(
        displayId: Int,
        width: Int,
        height: Int,
        flags: Int,
        uniqueId: String? = null,
        densityDpi: Int = 0,
    ): Boolean {
        if (displayId == Display.DEFAULT_DISPLAY) return false
        if ((flags and Display.FLAG_PRESENTATION) == 0) return false
        if (width != STREAM_WIDTH || height != STREAM_HEIGHT) return false
        if (!uniqueId.isNullOrBlank()) {
            return uniqueId.equals(CLUSTER_UNIQUE_ID, ignoreCase = true)
        }
        return displayId == FALLBACK_DISPLAY_ID && densityDpi == EXPECTED_DENSITY_DPI
    }

    fun matches(display: Display, width: Int, height: Int): Boolean {
        val uniqueId = readUniqueId(display)
        val densityDpi = readDensityDpi(display)
        return matches(
            displayId = display.displayId,
            width = width,
            height = height,
            flags = display.flags,
            uniqueId = uniqueId,
            densityDpi = densityDpi,
        )
    }

    /**
     * Builds a full-bleed 1920x720 cluster stream using explicit asymmetric safe-area insets
     * directly, avoiding symmetric marker-centered cropping that would clip the bottom status bar.
     */
    fun streamConfig(
        content: CarPlayClusterDisplay.Content,
        safeAreaRect: SafeAreaRect? = null,
    ): AirPlayDisplayConfig {
        val base = CarPlayClusterDisplay.config(
            STREAM_WIDTH,
            STREAM_HEIGHT,
            scalePercent = 100,
            horizontalStep = 0,
            verticalStep = 0,
            content = content,
        )
        val rect = safeAreaRect ?: defaultSafeAreaRect()
        return base.copy(
            safeArea = AirPlaySafeArea.toInsets(
                rect,
                STREAM_WIDTH,
                STREAM_HEIGHT,
                STREAM_WIDTH,
                STREAM_HEIGHT,
            ),
        )
    }

    private fun readUniqueId(display: Display): String? {
        runCatching {
            val method = Display::class.java.getMethod("getUniqueId")
            val value = method.invoke(display) as? String
            if (!value.isNullOrBlank()) return value
        }
        runCatching {
            val infoClass = Class.forName("android.view.DisplayInfo")
            val info = infoClass.getDeclaredConstructor().newInstance()
            val getter = Display::class.java.getMethod("getDisplayInfo", infoClass)
            if (getter.invoke(display, info) == true) {
                val field = infoClass.getField("uniqueId")
                val value = field.get(info) as? String
                if (!value.isNullOrBlank()) return value
            }
        }
        return null
    }

    private fun readDensityDpi(display: Display): Int = runCatching {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        metrics.densityDpi
    }.getOrDefault(0)
}
