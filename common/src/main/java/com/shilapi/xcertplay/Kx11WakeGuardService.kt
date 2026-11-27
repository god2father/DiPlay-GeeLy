package com.shilapi.xcertplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.Kx11DimSettings
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure, permission-free detector that distinguishes genuine Suspend-to-RAM (STR) vehicle wakes
 * from normal in-drive screen-off / screensaver toggles.
 *
 * During kernel suspend (`mem` / STR), [SystemClock.elapsedRealtime] continues advancing while
 * [SystemClock.uptimeMillis] stays frozen. Therefore `(elapsedDelta - uptimeDelta)` measures the
 * exact deep-sleep duration since the last baseline without needing privileged `CAR_POWER` APIs.
 */
internal class Kx11StrWakeDetector(
    private val minDeepSleepMs: Long = DEFAULT_MIN_DEEP_SLEEP_MS,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    enum class Decision {
        NO_BASELINE,
        SHALLOW_WAKE,
        COOLDOWN,
        STR_WAKE,
    }

    private var lastElapsedMs: Long? = null
    private var lastUptimeMs: Long? = null
    private var lastTriggerElapsedMs: Long? = null

    @Synchronized
    fun recordBaseline(elapsedMs: Long, uptimeMs: Long) {
        lastElapsedMs = elapsedMs
        lastUptimeMs = uptimeMs
    }

    @Synchronized
    fun onScreenOff(elapsedMs: Long, uptimeMs: Long) {
        lastElapsedMs = elapsedMs
        lastUptimeMs = uptimeMs
    }

    @Synchronized
    fun onScreenWake(elapsedMs: Long, uptimeMs: Long): Decision {
        val prevElapsed = lastElapsedMs
        val prevUptime = lastUptimeMs
        lastElapsedMs = elapsedMs
        lastUptimeMs = uptimeMs
        if (prevElapsed == null || prevUptime == null) return Decision.NO_BASELINE

        val elapsedDelta = (elapsedMs - prevElapsed).coerceAtLeast(0L)
        val uptimeDelta = (uptimeMs - prevUptime).coerceAtLeast(0L)
        val deepSleepMs = (elapsedDelta - uptimeDelta).coerceAtLeast(0L)
        if (deepSleepMs < minDeepSleepMs) return Decision.SHALLOW_WAKE

        val prevTrigger = lastTriggerElapsedMs
        if (prevTrigger != null && elapsedMs >= prevTrigger && elapsedMs - prevTrigger < cooldownMs) {
            return Decision.COOLDOWN
        }
        lastTriggerElapsedMs = elapsedMs
        return Decision.STR_WAKE
    }

    companion object {
        const val DEFAULT_MIN_DEEP_SLEEP_MS = 5_000L
        const val DEFAULT_COOLDOWN_MS = 30_000L
    }
}

/**
 * Lightweight foreground wake guard for KX11_A2.
 *
 * KX11_A2 resumes daily from STR sleep without broadcasting `ACTION_BOOT_COMPLETED`, while keeping
 * running processes alive across suspend. When "Open after the car starts" is enabled, this service
 * stays resident with a low-priority silent notification, detects real STR wake-ups via deep-sleep
 * clock drift, silently brings up the car hotspot (`uap0`), and launches [DiPlayActivity].
 */
class Kx11WakeGuardService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val detector = Kx11StrWakeDetector()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "diplay-kx11-wake-guard").apply { isDaemon = true }
    }
    private var screenReceiverRegistered = false
    private var displayListenerRegistered = false
    private var lastDefaultDisplayState = Display.STATE_UNKNOWN

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    detector.onScreenOff(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())
                }
                Intent.ACTION_SCREEN_ON -> {
                    handlePotentialWake("ACTION_SCREEN_ON")
                }
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val manager = getSystemService(DisplayManager::class.java) ?: return
            val state = manager.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: return
            val prev = lastDefaultDisplayState
            lastDefaultDisplayState = state
            if (state == Display.STATE_OFF) {
                detector.onScreenOff(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())
            } else if (state == Display.STATE_ON && prev == Display.STATE_OFF) {
                handlePotentialWake("DisplayListener")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        detector.recordBaseline(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())
        val displayManager = getSystemService(DisplayManager::class.java)
        lastDefaultDisplayState = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_UNKNOWN
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            registerReceiver(screenReceiver, filter)
            screenReceiverRegistered = true
        }.onFailure {
            Log.w(TAG, "registerReceiver failed: ${it.javaClass.simpleName}")
        }
        if (displayManager != null) {
            runCatching {
                displayManager.registerDisplayListener(displayListener, mainHandler)
                displayListenerRegistered = true
            }.onFailure {
                Log.w(TAG, "registerDisplayListener failed: ${it.javaClass.simpleName}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 8+ requires startForeground() after startForegroundService() even if stopping right away.
        startForegroundNotification()
        if (intent?.action == ACTION_BT_WAKE) {
            handleBtWake(intent.getStringExtra(EXTRA_WAKE_SOURCE) ?: "bluetooth")
            return if (shouldRun(this)) START_STICKY else START_NOT_STICKY
        }
        if (!shouldRun(this)) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_BOOT_WAKE) {
            handleBootWake(intent.getStringExtra(EXTRA_WAKE_SOURCE) ?: "boot")
        }
        return START_STICKY
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
        if (displayListenerRegistered) {
            runCatching { getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener) }
            displayListenerRegistered = false
        }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.kx11_wake_guard_channel),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    setShowBadge(false)
                },
            )
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
        }
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            buildLaunchIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = builder
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle("DiPlay")
            .setContentText(getString(R.string.kx11_wake_guard_running))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun handleBootWake(source: String) {
        if (!bootWakeHandled.compareAndSet(false, true)) {
            Log.d(TAG, "Boot wake already handled in this process (source=$source)")
            return
        }
        val app = applicationContext
        StartupDiagnosticSnapshot.received(app, true)
        if (hasOpenOrBackgroundCarPlay()) {
            Log.i(TAG, "Boot wake ($source): CarPlay already open/active, bringing CarPlay to front")
            bringCarPlayToFront(app, "boot-$source")
            return
        }
        val delayMs = bootLaunchDelayMs(SystemClock.uptimeMillis())
        Log.i(TAG, "Boot wake ($source): bringing up hotspot and launching DiPlay in ${delayMs}ms")
        scheduleHotspotEnable(app, "boot-$source", retryOnFailure = true)
        mainHandler.postDelayed({
            if (!shouldRun(app)) return@postDelayed
            if (hasOpenOrBackgroundCarPlay()) {
                bringCarPlayToFront(app, "boot-$source-delayed")
                return@postDelayed
            }
            launchDiPlayForAutoConnect(app, "boot-$source")
        }, delayMs)
    }

    private fun handleBtWake(source: String) {
        val finishIfTransient = {
            mainHandler.post {
                if (!shouldRun(this@Kx11WakeGuardService)) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
        val now = SystemClock.elapsedRealtime()
        val prev = lastBtWakeElapsedMs.get()
        if (prev > 0L && now >= prev && now - prev < BT_WAKE_COOLDOWN_MS) {
            Log.d(TAG, "BT wake ($source) suppressed by cooldown")
            finishIfTransient()
            return
        }
        lastBtWakeElapsedMs.set(now)
        val app = applicationContext
        Log.i(TAG, "BT wake ($source): ensuring hotspot and reconnecting CarPlay")
        scheduleHotspotEnable(app, "bt-$source", retryOnFailure = true, onComplete = finishIfTransient)
        mainHandler.post {
            resumeOrLaunchAfterWake(app, "bt-$source", "KX11 Bluetooth wake")
        }
    }

    private fun scheduleHotspotEnable(
        app: Context,
        label: String,
        retryOnFailure: Boolean,
        onComplete: () -> Unit = {},
    ) {
        if (!CarHotspotSettings.shouldEnable(
                app,
                AirPlayPersistence.loadWirelessEnabled(app),
                AirPlayPersistence.loadWirelessHotspotMode(app),
            )
        ) {
            onComplete()
            return
        }
        worker.execute {
            try {
                val first = runHotspotEnable(app, label)
                if (retryOnFailure && first != CarHotspotTethering.Result.READY && !worker.isShutdown) {
                    try {
                        Thread.sleep(BOOT_HOTSPOT_RETRY_DELAY_MS)
                    } catch (_: InterruptedException) {
                        return@execute
                    }
                    runHotspotEnable(app, "$label-retry")
                }
            } finally {
                onComplete()
            }
        }
    }

    private fun runHotspotEnable(app: Context, label: String): CarHotspotTethering.Result? {
        val result = runCatching {
            CarHotspotTethering.enable(
                context = app,
                isCancelled = { false },
                log = { msg -> Log.i(TAG, msg) },
            )
        }.onFailure {
            Log.w(TAG, "$label hotspot start threw: ${it.javaClass.simpleName}")
        }.getOrNull()
        Log.i(TAG, "$label hotspot result=$result")
        return result
    }

    private fun handlePotentialWake(source: String) {
        val decision = detector.onScreenWake(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())
        if (decision != Kx11StrWakeDetector.Decision.STR_WAKE) {
            Log.d(TAG, "Ignored wake from $source decision=$decision")
            return
        }
        if (!shouldRun(this)) {
            stopForegroundCompat()
            stopSelf()
            return
        }
        // Genuine STR wake: uap0 is typically torn down across deep sleep and any previous
        // CarPlayBackgroundSession may still be stale (`hasSession() == true, active == false`).
        // Always bring up uap0 first, then reconnect or launch without being blocked by stale sessions.
        Log.i(TAG, "STR wake detected ($source); bringing up hotspot and resuming CarPlay")
        val app = applicationContext
        scheduleHotspotEnable(app, "str-$source", retryOnFailure = true)
        mainHandler.post {
            resumeOrLaunchAfterWake(app, "str-$source", "KX11 STR wake")
        }
    }

    private fun resumeOrLaunchAfterWake(app: Context, label: String, reason: String) {
        if (CarPlayBackgroundSession.active) {
            bringCarPlayToFront(app, "$label-active")
            return
        }
        val openScreen = CarPlayScreen.current()
        if (openScreen != null) {
            openScreen.onVehicleStrWake(reason)
            bringCarPlayToFront(app, "$label-reconnect")
            return
        }
        if (CarPlayBackgroundSession.hasSession()) {
            // Stale background session whose Host Activity was already destroyed: tear it down
            // cleanly so DiPlay starts a fresh connection instead of adopting a dead controller.
            Log.i(TAG, "$label clearing stale background session without host activity")
            CarPlayBackgroundSession.stop {
                mainHandler.post {
                    launchDiPlayForAutoConnect(app, "$label-recreate")
                }
            }
            return
        }
        launchDiPlayForAutoConnect(app, label)
    }

    private fun hasOpenOrBackgroundCarPlay(): Boolean =
        CarPlayScreen.current() != null || CarPlayBackgroundSession.hasSession()

    private fun bringCarPlayToFront(app: Context, label: String) {
        val intent = CarPlayScreen.intent(app).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
        )
        runCatching {
            app.startActivity(intent)
            StartupDiagnosticSnapshot.launchResult(app)
            Log.i(TAG, "$label brought CarPlay screen to front")
        }.onFailure { error ->
            if (error is RuntimeException) StartupDiagnosticSnapshot.launchResult(app, error)
            Log.w(TAG, "$label could not bring CarPlay screen to front", error)
        }
    }

    private fun launchDiPlayForAutoConnect(app: Context, label: String) {
        try {
            app.startActivity(buildLaunchIntent(app, autoLaunch = true))
            StartupDiagnosticSnapshot.launchResult(app)
            Log.i(TAG, "$label launched DiPlayActivity (autoLaunch=true)")
        } catch (error: RuntimeException) {
            StartupDiagnosticSnapshot.launchResult(app, error)
            Log.w(TAG, "$label could not launch DiPlayActivity", error)
        }
    }

    companion object {
        private const val TAG = "DiPlay-KX11Wake"
        private const val CHANNEL_ID = "diplay_kx11_wake_guard"
        private const val NOTIFICATION_ID = 2
        const val ACTION_BOOT_WAKE = "com.shihab.diplay.ACTION_KX11_BOOT_WAKE"
        const val ACTION_BT_WAKE = "com.shihab.diplay.ACTION_KX11_BT_WAKE"
        const val EXTRA_WAKE_SOURCE = "wake_source"
        const val EXTRA_BOOT_AUTO_LAUNCH = "com.shihab.diplay.EXTRA_KX11_BOOT_AUTO_LAUNCH"
        private const val EARLY_BOOT_UPTIME_MS = 45_000L
        private const val EARLY_BOOT_LAUNCH_DELAY_MS = 6_000L
        private const val WARM_BOOT_LAUNCH_DELAY_MS = 1_500L
        private const val BOOT_HOTSPOT_RETRY_DELAY_MS = 5_000L
        private const val BT_WAKE_COOLDOWN_MS = 15_000L

        internal val bootWakeHandled = AtomicBoolean(false)
        internal val lastBtWakeElapsedMs = java.util.concurrent.atomic.AtomicLong(0L)
        internal val autoLaunchToken: String = java.util.UUID.randomUUID().toString()

        internal fun bootLaunchDelayMs(uptimeMs: Long): Long =
            if (uptimeMs < EARLY_BOOT_UPTIME_MS) EARLY_BOOT_LAUNCH_DELAY_MS else WARM_BOOT_LAUNCH_DELAY_MS

        internal fun shouldRun(context: Context, model: String = Build.MODEL ?: ""): Boolean =
            Kx11DimSettings.isSupported(model) && AirPlayPersistence.loadAutoStartOnBoot(context)

        fun triggerBootWake(context: Context, source: String = "BootReceiver") {
            val app = context.applicationContext
            if (!shouldRun(app)) return
            val intent = Intent(app, Kx11WakeGuardService::class.java).apply {
                action = ACTION_BOOT_WAKE
                putExtra(EXTRA_WAKE_SOURCE, source)
            }
            startWakeIntent(app, intent, "boot wake ($source)")
        }

        fun triggerBtWake(context: Context, source: String = "PhoneBluetoothReceiver") {
            val app = context.applicationContext
            if (!Kx11DimSettings.isSupported()) return
            val intent = Intent(app, Kx11WakeGuardService::class.java).apply {
                action = ACTION_BT_WAKE
                putExtra(EXTRA_WAKE_SOURCE, source)
            }
            startWakeIntent(app, intent, "BT wake ($source)")
        }

        private fun startWakeIntent(app: Context, intent: Intent, label: String) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            }.onFailure {
                Log.w(TAG, "Could not trigger $label: ${it.javaClass.simpleName}: ${it.message}")
            }
        }

        internal fun buildLaunchIntent(context: Context, autoLaunch: Boolean = false): Intent =
            Intent(context, DiPlayActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                if (autoLaunch) {
                    putExtra(EXTRA_BOOT_AUTO_LAUNCH, autoLaunchToken)
                }
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }

        fun sync(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, Kx11WakeGuardService::class.java)
            if (shouldRun(app)) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        app.startForegroundService(intent)
                    } else {
                        app.startService(intent)
                    }
                }.onFailure {
                    Log.w(TAG, "Could not start Kx11WakeGuardService: ${it.javaClass.simpleName}: ${it.message}")
                }
            } else {
                runCatching { app.stopService(intent) }
            }
        }
    }
}
