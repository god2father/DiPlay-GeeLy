package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioDeviceInfo
import com.shilapi.xcertplay.media.AudioOutputDevice
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class Kx11HeadrestAudioRouteTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPrefs() {
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("vehicle_audio_routes", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun matchesBus1NavigationOutRegardlessOfDeviceId() {
        val mediaBus = AudioOutputDevice(id = 3, type = AudioDeviceInfo.TYPE_BUS, address = "bus0_media_out", name = "KX11_A2")
        val navBus = AudioOutputDevice(id = 99, type = AudioDeviceInfo.TYPE_BUS, address = "bus1_navigation_out", name = "KX11_A2")
        val wrongType = AudioOutputDevice(id = 5, type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, address = "bus1_navigation_out", name = "KX11_A2")

        assertFalse(Kx11HeadrestAudioRoute.isHeadrestBus(mediaBus))
        assertFalse(Kx11HeadrestAudioRoute.isHeadrestBus(wrongType))
        assertTrue(Kx11HeadrestAudioRoute.isHeadrestBus(navBus))
        assertEquals(navBus, Kx11HeadrestAudioRoute.findDevice(listOf(mediaBus, wrongType, navBus)))
    }

    @Test
    fun stateDerivesFromVehicleAudioRoutesAndDisablingPreservesNonHeadrestSelections() {
        val navBus = AudioOutputDevice(id = 5, type = AudioDeviceInfo.TYPE_BUS, address = "bus1_navigation_out", name = "KX11_A2")
        val customBus = AudioOutputDevice(id = 8, type = AudioDeviceInfo.TYPE_BUS, address = "bus2_voice_command_out", name = "KX11_A2")

        assertFalse(Kx11HeadrestAudioRoute.isEnabled(app))
        VehicleAudioRoutes.set(app, VehicleAudioRole.NAVIGATION, navBus)
        assertTrue(Kx11HeadrestAudioRoute.isEnabled(app))

        assertTrue(Kx11HeadrestAudioRoute.setEnabled(app, false))
        assertFalse(Kx11HeadrestAudioRoute.isEnabled(app))
        assertNull(VehicleAudioRoutes.get(app, VehicleAudioRole.NAVIGATION))

        // Turning off must not wipe a different user-selected output device.
        VehicleAudioRoutes.set(app, VehicleAudioRole.NAVIGATION, customBus)
        assertFalse(Kx11HeadrestAudioRoute.isEnabled(app))
        assertTrue(Kx11HeadrestAudioRoute.setEnabled(app, false))
        assertEquals(customBus, VehicleAudioRoutes.get(app, VehicleAudioRole.NAVIGATION))
    }

    @Test
    fun kx11VehiclePropertiesPresetContainsOnlyVerifiedReadableBindings() {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var jsonFile: File? = null
        while (dir != null) {
            val candidate = File(dir, "vehicle-bridge/src/main/res/raw/xingyue_l_kx11_properties.json")
            if (candidate.isFile) {
                jsonFile = candidate
                break
            }
            dir = dir.parentFile
        }
        assertNotNull("xingyue_l_kx11_properties.json must exist", jsonFile)
        val content = jsonFile!!.readText()
        assertTrue(content.contains("\"RANGE\""))
        assertTrue(content.contains("\"AMBIENT_TEMPERATURE\""))
        assertTrue(content.contains("\"CABIN_TEMPERATURE\""))
        assertFalse("Unreadable SPEED should not be in KX11_A2 preset", content.contains("\"SPEED\""))
        assertFalse("Unreadable ODOMETER should not be in KX11_A2 preset", content.contains("\"ODOMETER\""))
    }
}
