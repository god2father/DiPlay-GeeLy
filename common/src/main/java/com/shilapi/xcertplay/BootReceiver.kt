package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shilapi.xcertplay.telecom.PhoneAudioRoute

/** Starts the CarPlay host after boot when the user has enabled the startup option. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val receivedAction = intent.action
        if (receivedAction != Intent.ACTION_BOOT_COMPLETED && receivedAction != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Starts the watcher for other apps' calls when that setting is on, even without opening DiPlay.
        PhoneAudioRoute.attach(context)
        Kx11WakeGuardService.sync(context)
        if (receivedAction == Intent.ACTION_MY_PACKAGE_REPLACED) return
        val launchEnabled = AirPlayPersistence.loadAutoStartOnBoot(context)
        StartupDiagnosticSnapshot.received(context, launchEnabled)
        if (!launchEnabled) return

        // On KX11_A2, delegate cold-boot launch through Kx11WakeGuardService so the car hotspot
        // (uap0) is brought up in the background and DiPlayActivity waits for the launcher.
        if (Kx11WakeGuardService.shouldRun(context)) {
            Kx11WakeGuardService.triggerBootWake(context)
            return
        }

        // Shaped like the launcher icon's intent: a USB authenticator or iPhone plugged in can open CarPlay
        // before this broadcast, and DiPlayActivity then returns to that session instead of showing Home over it.
        val launch = Intent(context, DiPlayActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        try {
            context.startActivity(launch)
            StartupDiagnosticSnapshot.launchResult(context)
        } catch (error: RuntimeException) {
            StartupDiagnosticSnapshot.launchResult(context, error)
            Log.w(TAG, "Boot auto-start could not launch DiPlayActivity", error)
        }
    }

    private companion object {
        const val TAG = "xcertplay-boot"
    }
}
