package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.network.CarHotspotSettings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class Kx11ProjectionDefaultsTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetPrefs() {
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("vehicle_map_projection", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("geely_projection_layout", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("diplay_kx11_migrations", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun resetsAllFiveHudAndProjectionTogglesOnceOnKx11A2() {
        AirPlayPersistence.saveGeelyHudEnabled(app, true)
        VehicleMapSettings.setEnabled(app, true)
        AirPlayPersistence.saveClusterMapEnabled(app, true)
        GeelyHudProjection.setThreeFingerEnabled(app, true)
        AirPlayPersistence.saveAdbClusterEnabled(app, true)

        // Simulate an older v1 migration marker to verify v2 still clears newly added toggles.
        app.getSharedPreferences("diplay_kx11_migrations", Context.MODE_PRIVATE)
            .edit().putBoolean("kx11_hud_reset_v1", true).commit()

        assertTrue(Kx11ProjectionDefaults.applyOnceIfNeeded(app, model = "KX11_A2"))
        assertFalse(AirPlayPersistence.loadGeelyHudEnabled(app))
        assertFalse(VehicleMapSettings.enabled(app))
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(app))
        assertFalse(GeelyHudProjection.threeFingerEnabled(app))
        assertFalse(AirPlayPersistence.loadAdbClusterEnabled(app))

        // Manual re-enabling by the driver must be preserved on subsequent launches.
        AirPlayPersistence.saveGeelyHudEnabled(app, true)
        VehicleMapSettings.setEnabled(app, true)
        GeelyHudProjection.setThreeFingerEnabled(app, true)
        assertFalse(Kx11ProjectionDefaults.applyOnceIfNeeded(app, model = "KX11_A2"))
        assertTrue(AirPlayPersistence.loadGeelyHudEnabled(app))
        assertTrue(VehicleMapSettings.enabled(app))
        assertTrue(GeelyHudProjection.threeFingerEnabled(app))
    }

    @Test
    fun doesNotModifyNonKx11Models() {
        AirPlayPersistence.saveGeelyHudEnabled(app, true)
        VehicleMapSettings.setEnabled(app, true)
        AirPlayPersistence.saveClusterMapEnabled(app, true)
        GeelyHudProjection.setThreeFingerEnabled(app, true)
        AirPlayPersistence.saveAdbClusterEnabled(app, true)

        assertFalse(Kx11ProjectionDefaults.applyOnceIfNeeded(app, model = "CS11_A4"))
        assertTrue(AirPlayPersistence.loadGeelyHudEnabled(app))
        assertTrue(VehicleMapSettings.enabled(app))
        assertTrue(AirPlayPersistence.loadClusterMapEnabled(app))
        assertTrue(GeelyHudProjection.threeFingerEnabled(app))
        assertTrue(AirPlayPersistence.loadAdbClusterEnabled(app))
    }

    @Test
    fun enablesAdbHotspotSettingsEligibilityOnKx11A2Android11() {
        assertTrue(CarHotspotSetup.supportsAdbHotspot(app, model = "KX11_A2"))
        assertFalse(CarHotspotSetup.supportsAdbHotspot(app, model = "CS11_A4"))

        val kx11Supported = CarHotspotSetup.supportsAdbHotspot(app, model = "KX11_A2")
        assertTrue(CarHotspotSettings.visible(kx11Supported, LocalAdb.Access.READY))
        assertTrue(CarHotspotSettings.visible(kx11Supported, LocalAdb.Access.NOT_APPROVED))
        assertFalse(CarHotspotSettings.visible(kx11Supported, LocalAdb.Access.UNREACHABLE))
    }
}
