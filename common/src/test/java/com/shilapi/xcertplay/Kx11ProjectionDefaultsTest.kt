package com.shilapi.xcertplay

import android.content.Context
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
        app.getSharedPreferences("diplay_kx11_migrations", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun resetsAllHudAndProjectionTogglesOnceOnKx11A2() {
        AirPlayPersistence.saveGeelyHudEnabled(app, true)
        VehicleMapSettings.setEnabled(app, true)
        AirPlayPersistence.saveClusterMapEnabled(app, true)

        assertTrue(Kx11ProjectionDefaults.applyOnceIfNeeded(app, model = "KX11_A2"))
        assertFalse(AirPlayPersistence.loadGeelyHudEnabled(app))
        assertFalse(VehicleMapSettings.enabled(app))
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(app))

        // Manual re-enabling by the driver must be preserved on subsequent launches.
        AirPlayPersistence.saveGeelyHudEnabled(app, true)
        VehicleMapSettings.setEnabled(app, true)
        assertFalse(Kx11ProjectionDefaults.applyOnceIfNeeded(app, model = "KX11_A2"))
        assertTrue(AirPlayPersistence.loadGeelyHudEnabled(app))
        assertTrue(VehicleMapSettings.enabled(app))
    }

    @Test
    fun doesNotModifyNonKx11Models() {
        AirPlayPersistence.saveGeelyHudEnabled(app, true)
        VehicleMapSettings.setEnabled(app, true)
        AirPlayPersistence.saveClusterMapEnabled(app, true)

        assertFalse(Kx11ProjectionDefaults.applyOnceIfNeeded(app, model = "CS11_A4"))
        assertTrue(AirPlayPersistence.loadGeelyHudEnabled(app))
        assertTrue(VehicleMapSettings.enabled(app))
        assertTrue(AirPlayPersistence.loadClusterMapEnabled(app))
    }
}
