package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.hud.Kx11DimSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class Kx11Stage2IntegrationTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetPrefs() {
        app.getSharedPreferences("diplay_kx11_dim", Context.MODE_PRIVATE).edit().clear().commit()
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
