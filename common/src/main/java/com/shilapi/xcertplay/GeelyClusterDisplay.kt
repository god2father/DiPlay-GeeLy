package com.shilapi.xcertplay

import android.os.Build
import android.view.Display
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.SafeAreaRect

/**
 * Geely FX11 / CS11_A4 head unit (`msmnile_gvmq`): the instrument cluster is an ordinary
 * 1920x720 presentation display, owned by no projection app. Any app may show a Presentation
 * on it; there is no BYD-style projection display, no projection command to issue and no
 * DiLink side-layer layout.
 *
 * Measured on the target head unit: the cluster is
 * displayId 2, 1920x720, flags 0x8b (`FLAG_PRESENTATION` set), and an ordinary third-party
 * `Presentation` becomes that display's focused window and renders.
 *
 * Selection is gated to this one head unit. Other ECARX firmware may expose a differently sized
 * or non-public cluster, and picking the wrong panel would put the map on a passenger screen.
 * The identifiers only admit the route; the display still has to look like this cluster.
 */
internal object GeelyClusterDisplay {

    /** Qualcomm's generic SA8155 board name: several brands' head units report it. */
    private const val DEVICE = "msmnile_gvmq"

    /** Product code of the head unit this route was measured on. */
    private const val PRODUCT = "geely_fx11"

    /** Model name that head unit reports. */
    private const val MODEL = "CS11_A4"

    /** 2025 Geely Xingyue L (KX11_A2) identifiers measured on real vehicle. */
    private const val KX11_DEVICE = "ecarx"
    private const val KX11_PRODUCT = "ecarx"
    private const val KX11_MODEL = "KX11_A2"

    /** Measured on KX11_A2: Display 2 is passenger screen (PsdActivity), Display 4 is stock nav Presentation panel. */
    const val KX11_CLUSTER_DISPLAY_ID = 4

    /** The cluster stream is the panel's own size: the cluster scales nothing. */
    const val STREAM_WIDTH = 1920
    const val STREAM_HEIGHT = 720

    fun isKx11A2(
        device: String = Build.DEVICE,
        product: String = Build.PRODUCT,
        model: String = Build.MODEL,
    ): Boolean = device.equals(KX11_DEVICE, ignoreCase = true) &&
        product.equals(KX11_PRODUCT, ignoreCase = true) &&
        model.equals(KX11_MODEL, ignoreCase = true)

    fun supported(
        device: String = Build.DEVICE,
        product: String = Build.PRODUCT,
        model: String = Build.MODEL,
    ): Boolean = (device.equals(DEVICE, ignoreCase = true) &&
        product.equals(PRODUCT, ignoreCase = true) &&
        model.equals(MODEL, ignoreCase = true)) ||
        isKx11A2(device, product, model)

    /**
     * The cluster is the non-default presentation panel at the stream size. On KX11_A2,
     * display 2 is the passenger screen (1920x720 HDMI), so lock to display 4.
     */
    fun matches(displayId: Int, width: Int, height: Int, flags: Int): Boolean {
        if (displayId == Display.DEFAULT_DISPLAY ||
            (flags and Display.FLAG_PRESENTATION) == 0 ||
            width != STREAM_WIDTH || height != STREAM_HEIGHT
        ) return false
        return if (isKx11A2()) displayId == KX11_CLUSTER_DISPLAY_ID else true
    }

    fun matches(display: Display, width: Int, height: Int): Boolean =
        matches(display.displayId, width, height, display.flags)

    /**
     * A full-panel 1920x720 stream. The cluster keeps its own readouts outside
     * [safeAreaRect], which the cluster-map settings editor produces.
     */
    fun streamConfig(
        content: CarPlayClusterDisplay.Content,
        safeAreaRect: SafeAreaRect? = null,
        markerXPercent: Int? = null,
        markerYPercent: Int? = null,
    ): AirPlayDisplayConfig {
        val config = CarPlayClusterDisplay.config(
            STREAM_WIDTH,
            STREAM_HEIGHT,
            scalePercent = 100,
            horizontalStep = 0,
            verticalStep = 0,
            content = content,
            markerXPercent = markerXPercent,
            markerYPercent = markerYPercent,
        )
        return if (safeAreaRect == null) config else config.copy(safeArea = AirPlaySafeArea.toInsets(
            safeAreaRect, STREAM_WIDTH, STREAM_HEIGHT, STREAM_WIDTH, STREAM_HEIGHT))
    }
}
