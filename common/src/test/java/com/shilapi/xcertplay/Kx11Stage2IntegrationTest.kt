package com.shilapi.xcertplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.shilapi.xcertplay.hud.Kx11DimSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class Kx11Stage2IntegrationTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetPrefs() {
        app.getSharedPreferences("diplay_kx11_dim", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("diplay_kx11_migrations", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun dimNaviAndClusterMediaDefaultEnabledOnKx11A2AndDisabledElsewhere() {
        assertTrue(Kx11DimSettings.isSupported("KX11_A2"))
        assertTrue(Kx11DimSettings.naviHudEnabled(app, model = "KX11_A2"))
        assertTrue(Kx11DimSettings.clusterMediaEnabled(app, model = "KX11_A2"))
        assertTrue(Kx11DimSettings.noDuckMusicEnabled(app, model = "KX11_A2"))
        assertFalse(Kx11DimSettings.clusterFullNaviLayoutEnabled(app, model = "KX11_A2"))

        assertFalse(Kx11DimSettings.isSupported("CS11_A4"))
        assertFalse(Kx11DimSettings.naviHudEnabled(app, model = "CS11_A4"))
        assertFalse(Kx11DimSettings.clusterMediaEnabled(app, model = "CS11_A4"))
        assertFalse(Kx11DimSettings.noDuckMusicEnabled(app, model = "CS11_A4"))
        assertFalse(Kx11DimSettings.clusterFullNaviLayoutEnabled(app, model = "CS11_A4"))

        // User opt-out is persisted and respected
        Kx11DimSettings.setNaviHudEnabled(app, false)
        Kx11DimSettings.setClusterMediaEnabled(app, false)
        assertFalse(Kx11DimSettings.naviHudEnabled(app, model = "KX11_A2"))
        assertFalse(Kx11DimSettings.clusterMediaEnabled(app, model = "KX11_A2"))
    }

    @Test
    fun strWakeDetectorDistinguishesGenuineSuspendFromInDriveScreenToggleAndEnforcesCooldown() {
        val detector = Kx11StrWakeDetector(minDeepSleepMs = 5_000L, cooldownMs = 30_000L)

        // First wake without baseline records baseline and does not trigger
        assertEquals(Kx11StrWakeDetector.Decision.NO_BASELINE, detector.onScreenWake(10_000L, 10_000L))

        // Driver turns off screen for 2 minutes while driving (no kernel suspend -> elapsed == uptime)
        detector.onScreenOff(20_000L, 20_000L)
        assertEquals(
            Kx11StrWakeDetector.Decision.SHALLOW_WAKE,
            detector.onScreenWake(140_000L, 140_000L),
        )

        // Overnight STR suspend: elapsed advances by 8 hours, uptime only advances by 300ms
        detector.onScreenOff(200_000L, 200_000L)
        val afterOvernightElapsed = 200_000L + 8 * 3_600_000L
        val afterOvernightUptime = 200_000L + 300L
        assertEquals(
            Kx11StrWakeDetector.Decision.STR_WAKE,
            detector.onScreenWake(afterOvernightElapsed, afterOvernightUptime),
        )

        // Duplicate wake signal (e.g., ACTION_SCREEN_ON + DisplayListener) within 30s is suppressed
        detector.onScreenOff(afterOvernightElapsed + 1_000L, afterOvernightUptime + 1_000L)
        assertEquals(
            Kx11StrWakeDetector.Decision.COOLDOWN,
            detector.onScreenWake(afterOvernightElapsed + 12_000L, afterOvernightUptime + 2_000L),
        )

        // Next STR sleep after cooldown triggers cleanly again
        detector.onScreenOff(afterOvernightElapsed + 40_000L, afterOvernightUptime + 30_000L)
        assertEquals(
            Kx11StrWakeDetector.Decision.STR_WAKE,
            detector.onScreenWake(afterOvernightElapsed + 100_000L, afterOvernightUptime + 30_500L),
        )
    }

    @Test
    fun wakeGuardRunsOnlyWhenKx11AndAutoStartOnBootEnabled() {
        assertFalse(Kx11WakeGuardService.shouldRun(app, model = "KX11_A2"))
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        assertTrue(Kx11WakeGuardService.shouldRun(app, model = "KX11_A2"))
        assertFalse(Kx11WakeGuardService.shouldRun(app, model = "CS11_A4"))
    }

    @Test
    fun manifestsDeclareRequiredDimQueriesArtworkProviderAndWakeGuard() {
        val repoRoot = locateRepoRoot()
        val sharedManifest = File(repoRoot, "shared/src/main/AndroidManifest.xml").readText()
        val commonManifest = File(repoRoot, "common/src/main/AndroidManifest.xml").readText()

        assertTrue(
            "shared manifest must query com.autolink.diminteraction for Android 11+ visibility",
            sharedManifest.contains("com.autolink.diminteraction"),
        )
        assertTrue(
            "shared manifest must query com.geely.dimservice for DimMenuInteraction visibility",
            sharedManifest.contains("com.geely.dimservice"),
        )
        assertTrue(
            "shared manifest must query com.ecarx.car for AdaptAPI visibility",
            sharedManifest.contains("com.ecarx.car"),
        )
        assertTrue(
            "shared manifest must query GEELY_DIM_MENU_SERVICE action",
            sharedManifest.contains("android.intent.action.GEELY_DIM_MENU_SERVICE"),
        )
        assertTrue(
            "common manifest must declare Kx11ArtworkProvider",
            commonManifest.contains("com.shilapi.xcertplay.Kx11ArtworkProvider"),
        )
        assertTrue(
            "common manifest must declare Kx11WakeGuardService",
            commonManifest.contains("com.shilapi.xcertplay.Kx11WakeGuardService"),
        )
        assertTrue(
            "BootReceiver must handle MY_PACKAGE_REPLACED for wake guard resurrection after update",
            commonManifest.contains("android.intent.action.MY_PACKAGE_REPLACED"),
        )
        assertTrue(
            "Kx11WakeGuardService must be protected by signature VEHICLE_BRIDGE permission when exported",
            commonManifest.contains("android:permission=\"com.geely.desktop.permission.VEHICLE_BRIDGE\""),
        )
    }

    @Test
    fun geelyMediaKeyInterceptionRespectsPermanentFocusLossAndForegroundResumeGuard() {
        // Normal playback or transient duck/interruption (permanentFocusLost=false) always keeps media keys.
        assertTrue(CarPlayMediaKeys.shouldInterceptGeelyMediaKeys(permanentFocusLost = false, ownsAudioFocus = true, hostResumed = true, otherMusicActive = false))
        assertTrue(CarPlayMediaKeys.shouldInterceptGeelyMediaKeys(permanentFocusLost = false, ownsAudioFocus = false, hostResumed = false, otherMusicActive = true))

        // After permanent AUDIOFOCUS_LOSS (OEM NetEase playing): release wheel media keys even if in foreground.
        assertFalse(CarPlayMediaKeys.shouldInterceptGeelyMediaKeys(permanentFocusLost = true, ownsAudioFocus = false, hostResumed = false, otherMusicActive = true))
        assertFalse(CarPlayMediaKeys.shouldInterceptGeelyMediaKeys(permanentFocusLost = true, ownsAudioFocus = false, hostResumed = true, otherMusicActive = true))
        assertFalse(CarPlayMediaKeys.shouldInterceptGeelyMediaKeys(permanentFocusLost = true, ownsAudioFocus = false, hostResumed = false, otherMusicActive = false))

        // Deadlock guard: OEM music stopped and driver returned to CarPlay foreground -> allow play key to resume CarPlay.
        assertTrue(CarPlayMediaKeys.shouldInterceptGeelyMediaKeys(permanentFocusLost = true, ownsAudioFocus = false, hostResumed = true, otherMusicActive = false))

        // Regained audio focus -> always intercept media keys again.
        assertTrue(CarPlayMediaKeys.shouldInterceptGeelyMediaKeys(permanentFocusLost = true, ownsAudioFocus = true, hostResumed = false, otherMusicActive = false))
    }

    @Test
    fun filterGeelyBridgeKeysStripsMediaKeysWhileKeepingVoiceKey() {
        val defaultKeys = intArrayOf(200085, 200087, 200088, 200231, 210005, 210006)
        assertEquals(defaultKeys.toList(), CarPlayMediaKeys.filterGeelyBridgeKeys(defaultKeys, true).toList())
        assertEquals(listOf(200231), CarPlayMediaKeys.filterGeelyBridgeKeys(defaultKeys, false).toList())
        // Media-only custom profile drops to empty so updateBridgeKeys closes the bridge lease cleanly.
        val mediaOnly = intArrayOf(200085, 200087, 200088)
        assertTrue(CarPlayMediaKeys.filterGeelyBridgeKeys(mediaOnly, false).isEmpty())
    }

    @Test
    fun bootWakeDelayWaitsLongerDuringEarlyBoot() {
        assertEquals(6_000L, Kx11WakeGuardService.bootLaunchDelayMs(15_000L))
        assertEquals(1_500L, Kx11WakeGuardService.bootLaunchDelayMs(60_000L))
    }

    @Test
    fun kx11FindDisplaySelectsDisplay4AcrossAllDisplaysAndRejectsPassengerAndHudDisplays() {
        val dm = app.getSystemService(DisplayManager::class.java)
        val addedIds = mutableListOf<Int>()
        try {
            // Simulate KX11_A2's real 5-display topology:
            // id1=TYPE_INTERNAL(1) 800x480 local:132, id2=TYPE_EXTERNAL(2) 1920x720 160dpi local:5,
            // id3=TYPE_INTERNAL(1) 1920x720 320dpi local:130 (HUD), id4=TYPE_INTERNAL(1) 1920x720 320dpi local:131 (cluster).
            val id1 = ShadowDisplayManager.addDisplay("w400dp-h240dp-xhdpi", 1).also { addedIds += it }
            val id2 = ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi", 2).also { addedIds += it }
            val id3 = ShadowDisplayManager.addDisplay("w960dp-h360dp-xhdpi", 1).also { addedIds += it }
            val id4 = ShadowDisplayManager.addDisplay("w960dp-h360dp-xhdpi", 1).also { addedIds += it }
            listOf(id1, id2, id3, id4).forEach { id ->
                shadowOf(dm.getDisplay(id)).setFlags(0x8b)
            }

            // Confirm AOSP DISPLAY_CATEGORY_PRESENTATION omits internal Display 4 just like KX11_A2 firmware.
            val presentationIds = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).map { it.displayId }
            assertFalse("Presentation category should omit internal Display 4", id4 in presentationIds)

            // 1) Primary uniqueId path: matches local:131 and rejects local:5 (passenger) and local:130 (HUD).
            setDisplayUniqueId(id1, "local:132")
            setDisplayUniqueId(id2, "local:5")
            setDisplayUniqueId(id3, "local:130")
            setDisplayUniqueId(id4, "local:131")

            ShadowBuild.setModel("KX11_A2")
            assertEquals(id4, ClusterMapPresentation.findDisplay(app)?.displayId)

            // Non-KX11 model must not select KX11's Display 4 via KX11 path.
            ShadowBuild.setModel("GENERIC_CAR")
            assertNull(ClusterMapPresentation.findDisplay(app))

            // 2) Fallback path when uniqueId is null: requires displayId == 4 && densityDpi == 320.
            listOf(id1, id2, id3, id4).forEach { setDisplayUniqueId(it, null) }
            assertEquals(4, id4)
            ShadowBuild.setModel("KX11_A2")
            assertEquals(4, ClusterMapPresentation.findDisplay(app)?.displayId)
        } finally {
            ShadowBuild.reset()
            addedIds.forEach { ShadowDisplayManager.removeDisplay(it) }
        }
    }

    private fun setDisplayUniqueId(displayId: Int, uniqueId: String?) {
        val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val dmg = dmgClass.getMethod("getInstance").invoke(null)
        val info = dmgClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(dmg, displayId)!!
        info.javaClass.getField("uniqueId").set(info, uniqueId)
        val shadowDmg = org.robolectric.shadow.api.Shadow.extract<Any>(dmg)
        shadowDmg.javaClass.getDeclaredMethod("changeDisplay", Int::class.javaPrimitiveType, info.javaClass)
            .apply { isAccessible = true }
            .invoke(shadowDmg, displayId, info)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    @Test
    fun kx11SyncFullNaviAlignsLegacyClusterMapFlagOnceWithoutFightingOptOut() {
        // Legacy Stage 2.2 state: full-navi layout enabled while cluster_map_enabled stayed false -> aligned once
        Kx11DimSettings.setClusterFullNaviLayoutEnabled(app, true)
        assertTrue(Kx11ClusterDisplay.syncFullNaviClusterMapEnabled(app, model = "KX11_A2"))
        assertTrue(AirPlayPersistence.loadClusterMapEnabled(app))
        // Second call is idempotent no-op and never fights a later opt-out
        AirPlayPersistence.saveClusterMapEnabled(app, false)
        assertFalse(Kx11ClusterDisplay.syncFullNaviClusterMapEnabled(app, model = "KX11_A2"))
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(app))

        // Fresh install on non-KX11 device is never touched
        resetPrefs()
        Kx11DimSettings.setClusterFullNaviLayoutEnabled(app, true)
        assertFalse(Kx11ClusterDisplay.syncFullNaviClusterMapEnabled(app, model = "CS11_A4"))
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(app))
    }

    private fun locateRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, "shared/src/main/AndroidManifest.xml").isFile &&
                File(dir, "common/src/main/AndroidManifest.xml").isFile
            ) {
                return dir
            }
            dir = dir.parentFile
        }
        error("Could not locate DiPlay repository root from ${System.getProperty("user.dir")}")
    }
}
