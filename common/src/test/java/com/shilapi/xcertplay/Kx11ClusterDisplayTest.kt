package com.shilapi.xcertplay

import android.view.Display
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.SafeAreaRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Kx11ClusterDisplayTest {

    @Test
    fun supportedOnlyOnKx11A2() {
        assertTrue(Kx11ClusterDisplay.supported("KX11_A2"))
        assertTrue(Kx11ClusterDisplay.supported("kx11_a2"))
        assertFalse(Kx11ClusterDisplay.supported("CS11_A4"))
        assertFalse(Kx11ClusterDisplay.supported("FX11"))
    }

    @Test
    fun matchesLocksPhysicalPortLocal131AndRejectsPassengerAndHudDisplays() {
        val flags = Display.FLAG_PRESENTATION
        // True cluster panel (local:131) matches even if HWC enumerates it as displayId 3.
        assertTrue(Kx11ClusterDisplay.matches(4, 1920, 720, flags, uniqueId = "local:131", densityDpi = 320))
        assertTrue(Kx11ClusterDisplay.matches(3, 1920, 720, flags, uniqueId = "local:131", densityDpi = 320))

        // Passenger screen (local:5, 160dpi) and Display 3 secondary/HUD (local:130, 320dpi) must NEVER match.
        assertFalse(Kx11ClusterDisplay.matches(2, 1920, 720, flags, uniqueId = "local:5", densityDpi = 160))
        assertFalse(Kx11ClusterDisplay.matches(3, 1920, 720, flags, uniqueId = "local:130", densityDpi = 320))
        assertFalse(Kx11ClusterDisplay.matches(4, 1920, 720, flags, uniqueId = "local:130", densityDpi = 320))
        assertFalse(Kx11ClusterDisplay.matches(1, 800, 480, flags, uniqueId = "local:132", densityDpi = 320))
        assertFalse(Kx11ClusterDisplay.matches(Display.DEFAULT_DISPLAY, 1920, 720, flags, uniqueId = "local:1", densityDpi = 160))

        // Fallback when uniqueId is unavailable requires both displayId == 4 and densityDpi == 320.
        assertTrue(Kx11ClusterDisplay.matches(4, 1920, 720, flags, uniqueId = null, densityDpi = 320))
        assertFalse(Kx11ClusterDisplay.matches(4, 1920, 720, flags, uniqueId = null, densityDpi = 160))
        assertFalse(Kx11ClusterDisplay.matches(2, 1920, 720, flags, uniqueId = null, densityDpi = 320))
    }

    @Test
    fun streamConfigUsesFullBleed1920x720WithAsymmetricDigitalClusterSafeArea() {
        val config = Kx11ClusterDisplay.streamConfig(CarPlayClusterDisplay.Content.MAP)
        assertEquals(1920, config.widthPixels)
        assertEquals(720, config.heightPixels)
        val safeArea = config.safeArea
        assertNotNull(safeArea)
        // Must clear 20% top digital speed bar and 14% bottom status bar, with 4% side margins (not BYD 35% dials).
        assertEquals(76, safeArea!!.left)
        assertEquals(144, safeArea.top)
        assertEquals(76, safeArea.right)
        assertEquals(100, safeArea.bottom)

        val custom = Kx11ClusterDisplay.streamConfig(
            CarPlayClusterDisplay.Content.MAP,
            safeAreaRect = SafeAreaRect(100, 160, 1820, 600),
        )
        assertEquals(100, custom.safeArea!!.left)
        assertEquals(160, custom.safeArea!!.top)
        assertEquals(100, custom.safeArea!!.right)
        assertEquals(120, custom.safeArea!!.bottom)
    }
}
