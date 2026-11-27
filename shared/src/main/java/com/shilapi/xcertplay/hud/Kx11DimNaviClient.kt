package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.iap2.message.Iap2LaneGuidance
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/**
 * Low-level Binder proxy speaking directly to KX11_A2's exported
 * `com.autolink.diminteraction/.DIMNaviInteractioncService` with zero vendor JAR dependencies.
 * Transaction codes match the `IDimNaviInteractioncService` Binder wire protocol.
 */
internal class Kx11DimNaviBinder(private val remote: IBinder) {
    fun notifyNavigationStatus(status: Int) = transactInt(TX_NOTIFY_NAVIGATION_STATUS, status)
    fun updateNavigationTurnId(turnId: Int) = transactInt(TX_UPDATE_NAVIGATION_TURN_ID, turnId)
    fun updateDistanceToDestination(meters: Int) = transactInt(TX_UPDATE_DISTANCE_TO_DESTINATION, meters)
    fun updateDistanceToNextGuidancePoint(meters: Int) = transactInt(TX_UPDATE_DISTANCE_TO_NEXT_GUIDANCE, meters)
    fun updateETA(remainingSeconds: Int) = transactInt(TX_UPDATE_ETA, remainingSeconds)
    fun updateNextGuidancePointName(name: String) = transactString(TX_UPDATE_NEXT_GUIDANCE_POINT_NAME, name)

    fun updateLaneInfo(laneInfo: Array<String>?) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeStringArray(laneInfo)
            data.writeTypedArray<android.graphics.Bitmap>(null, 0)
            remote.transact(TX_UPDATE_LANE_INFO, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactInt(code: Int, value: Int) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(value)
            remote.transact(code, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactString(code: Int, value: String) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(value)
            remote.transact(code, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    companion object {
        const val PACKAGE_NAME = "com.autolink.diminteraction"
        const val SERVICE_CLASS = "com.autolink.diminteraction.DIMNaviInteractioncService"
        const val DESCRIPTOR = "com.autolink.adapterbinder.IDimNaviInteractioncService"

        const val STATUS_UNNAVI = 0
        const val STATUS_SUCCEED = 1

        const val TX_NOTIFY_NAVIGATION_STATUS = 1
        const val TX_UPDATE_NAVIGATION_TURN_ID = 2
        const val TX_UPDATE_DISTANCE_TO_DESTINATION = 7
        const val TX_UPDATE_DISTANCE_TO_NEXT_GUIDANCE = 8
        const val TX_UPDATE_ETA = 12
        const val TX_UPDATE_LANE_INFO = 19
        const val TX_UPDATE_NEXT_GUIDANCE_POINT_NAME = 23
    }
}

/**
 * Stateful KX11_A2 OEM AR-HUD & cluster navigation publisher.
 *
 * Designed specifically around the verified quirks of `DIMNaviInteractioncService`:
 * 1. `OnceExecute.FINISH (moment=2)` wipes the server's `DimNaviInteraction.Builder`, so every
 *    emission replays ALL active fields in strict order before `updateETA()`.
 * 2. `mUpdateETA == data` suppresses emissions inside `OnceExecute`; when other fields change
 *    while `remainingSeconds` is unchanged, we toggle the least-significant second (`xor 1`) so
 *    QNX (which renders minutes) always receives fresh turn/distance/lane updates with zero drops.
 * 3. `0x5201 / 0x5202` and `0x5204` arriving in a burst are coalesced via a ~200ms trailing window.
 * 4. Only `notifyNavigationStatus(1)` (active) and `0` (stopped) are sent; `2` and
 *    `notifyTurnByTurnEvent` are never sent.
 */
internal class Kx11DimNaviPublisher(
    private val binderProvider: () -> IBinder?,
    private val scheduleDelayed: (delayMs: Long, action: Runnable) -> Unit,
    private val cancelScheduled: (action: Runnable) -> Unit,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val nanoTime: () -> Long = System::nanoTime,
    private val coalesceMs: Long = COALESCE_WINDOW_MS,
    private val statusRefreshMs: Long = STATUS_REFRESH_INTERVAL_MS,
    private val onNaviActiveChanged: (Boolean) -> Unit = {},
) {
    private data class RenderedFrame(
        val turnId: Int,
        val road: String,
        val distanceMeters: Int,
        val remainingMeters: Int,
        val remainingSeconds: Int,
        val lanes: List<String>,
    )

    private val lock = Any()
    private val routeState = BydHudRouteState(nanoTime = nanoTime)
    private var currentLanes: List<LaneGuidanceLane> = emptyList()
    private var routeActive = false
    private var naviActiveOnDim = false
    private var lastStatusSentMs = 0L
    private var lastTurnId = -1
    private var lastFrame: RenderedFrame? = null
    private var lastSentEta: Int? = null
    private var enabled = true

    private val flushTask = Runnable { flushNow() }

    fun setEnabled(next: Boolean) {
        val shouldClearHud = synchronized(lock) {
            if (enabled == next) return
            enabled = next
            !next
        }
        if (shouldClearHud) clearHudOutput()
    }

    fun onFrame(frame: Iap2Frame) {
        val shouldClear: Boolean
        val shouldSchedule: Boolean
        val notifyBecameActive: Boolean
        synchronized(lock) {
            when (frame.messageId) {
                BydHudRouteState.ROUTE_GUIDANCE_UPDATE,
                BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE -> {
                    val change = routeState.accept(frame.messageId, frame.payload)
                    if (change == BydHudRouteChange.CLEAR) {
                        currentLanes = emptyList()
                        shouldClear = true
                        shouldSchedule = false
                        notifyBecameActive = false
                    } else {
                        val hasGuidance = routeState.currentApple() != null
                        notifyBecameActive = hasGuidance && !routeActive
                        if (notifyBecameActive) routeActive = true
                        shouldClear = false
                        shouldSchedule = enabled && change == BydHudRouteChange.GUIDANCE && hasGuidance
                    }
                }
                Iap2LaneGuidance.LANE_GUIDANCE_INFO_UPDATE -> {
                    currentLanes = Iap2LaneGuidance.parse(frame.payload)
                    shouldClear = false
                    shouldSchedule = enabled && routeState.currentApple() != null
                    notifyBecameActive = false
                }
                else -> return
            }
        }
        if (notifyBecameActive) {
            runCatching { onNaviActiveChanged(true) }
        }
        if (shouldClear) {
            clear()
        } else if (shouldSchedule) {
            cancelScheduled(flushTask)
            scheduleDelayed(coalesceMs, flushTask)
        }
    }

    /** Clears OEM HUD output without wiping active route tracking (e.g. HUD off, cluster map on). */
    private fun clearHudOutput() {
        cancelScheduled(flushTask)
        val needDimReset = synchronized(lock) {
            lastFrame = null
            lastTurnId = -1
            lastSentEta = null
            lastStatusSentMs = 0L
            val wasActive = naviActiveOnDim
            naviActiveOnDim = false
            wasActive
        }
        if (!needDimReset) return
        sendClearToDim()
    }

    fun clear() {
        cancelScheduled(flushTask)
        val wasRouteActive: Boolean
        val needDimReset: Boolean
        synchronized(lock) {
            routeState.clear()
            currentLanes = emptyList()
            lastFrame = null
            lastTurnId = -1
            lastSentEta = null
            lastStatusSentMs = 0L
            wasRouteActive = routeActive
            routeActive = false
            needDimReset = naviActiveOnDim
            naviActiveOnDim = false
        }
        if (wasRouteActive || needDimReset) {
            runCatching { onNaviActiveChanged(false) }
        }
        if (!needDimReset) return
        sendClearToDim()
    }

    private fun sendClearToDim() {
        val remote = binderProvider() ?: return
        runCatching {
            val api = Kx11DimNaviBinder(remote)
            api.updateLaneInfo(null)
            api.updateNavigationTurnId(Kx11DimNaviMapper.TURN_NONE)
            api.updateDistanceToNextGuidancePoint(0)
            api.updateDistanceToDestination(0)
            api.updateNextGuidancePointName("")
            api.notifyNavigationStatus(Kx11DimNaviBinder.STATUS_UNNAVI)
        }.onFailure {
            Log.w(TAG, "clear failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    fun flushNow() {
        val frameToEmit: RenderedFrame
        val needStatusAssert: Boolean
        val etaToSend: Int
        synchronized(lock) {
            if (!enabled) return
            val apple = routeState.currentApple()
            if (apple == null) {
                if (naviActiveOnDim) {
                    // Route expired / became inactive without an explicit CLEAR frame.
                    // Release lock before sending clear sequence.
                    frameToEmit = RenderedFrame(-1, "", 0, 0, 0, emptyList())
                    needStatusAssert = false
                    etaToSend = 0
                } else {
                    return
                }
            } else {
                val turnId = Kx11DimNaviMapper.turnId(apple.type, apple.drivingSide)
                val encodedLanes = Kx11DimNaviMapper.encodeLanes(currentLanes)?.toList().orEmpty()
                val rawEta = apple.remainingSeconds?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0
                val remainingMeters = apple.remainingMeters?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0
                val next = RenderedFrame(
                    turnId = turnId,
                    road = apple.road,
                    distanceMeters = apple.distanceMeters.coerceAtLeast(0),
                    remainingMeters = remainingMeters,
                    remainingSeconds = rawEta,
                    lanes = encodedLanes,
                )
                if (next == lastFrame && naviActiveOnDim) return
                val now = elapsedRealtimeMs()
                needStatusAssert = !naviActiveOnDim || turnId != lastTurnId || (now - lastStatusSentMs >= statusRefreshMs)
                etaToSend = chooseDistinctEta(rawEta, lastSentEta)
                frameToEmit = next
            }
        }

        if (frameToEmit.turnId < 0) {
            clear()
            return
        }

        val remote = binderProvider() ?: return
        runCatching {
            val api = Kx11DimNaviBinder(remote)
            if (needStatusAssert) {
                api.notifyNavigationStatus(Kx11DimNaviBinder.STATUS_SUCCEED)
            }
            api.updateNavigationTurnId(frameToEmit.turnId)
            api.updateNextGuidancePointName(frameToEmit.road)
            api.updateDistanceToNextGuidancePoint(frameToEmit.distanceMeters)
            api.updateDistanceToDestination(frameToEmit.remainingMeters)
            api.updateLaneInfo(frameToEmit.lanes.takeIf { it.isNotEmpty() }?.toTypedArray())
            // Must be last: updateETA triggers DIMNaviInteractioncService's QNX FDBus transmission.
            api.updateETA(etaToSend)
            var becameActive = false
            synchronized(lock) {
                becameActive = !routeActive
                routeActive = true
                naviActiveOnDim = true
                lastFrame = frameToEmit
                lastTurnId = frameToEmit.turnId
                lastSentEta = etaToSend
                if (needStatusAssert) lastStatusSentMs = elapsedRealtimeMs()
            }
            if (becameActive) runCatching { onNaviActiveChanged(true) }
        }.onFailure {
            Log.w(TAG, "emit failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun chooseDistinctEta(rawSeconds: Int, previousSent: Int?): Int {
        if (previousSent == null || rawSeconds != previousSent) return rawSeconds
        val toggled = if (rawSeconds > 0) rawSeconds xor 1 else 1
        return if (toggled != previousSent) toggled else rawSeconds + 1
    }

    companion object {
        private const val TAG = "DiPlay-KX11DimNavi"
        const val COALESCE_WINDOW_MS = 200L
        const val STATUS_REFRESH_INTERVAL_MS = 5_000L
    }
}

/** Process-wide Android lifecycle host for KX11_A2 OEM DIM navigation output. */
object Kx11DimNaviBridge {
    private const val TAG = "DiPlay-KX11DimNavi"

    private val lock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var remoteBinder: IBinder? = null
    private var bound = false
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var publisher: Kx11DimNaviPublisher? = null
    @Volatile private var naviActive = false
    @Volatile private var clusterNaviListener: ((Boolean) -> Unit)? = null

    fun isNaviActive(): Boolean = naviActive

    fun setClusterNaviActiveListener(listener: ((Boolean) -> Unit)?) {
        clusterNaviListener = listener
        listener?.invoke(naviActive)
    }

    fun clearClusterNaviActiveListener(expected: (Boolean) -> Unit) {
        if (clusterNaviListener === expected) {
            clusterNaviListener = null
        }
    }

    private val deathRecipient = IBinder.DeathRecipient {
        synchronized(lock) {
            remoteBinder = null
            bound = false
        }
        Log.w(TAG, "DIMNaviInteractioncService binder died; will rebind on next update")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            synchronized(lock) {
                remoteBinder = service
                runCatching { service?.linkToDeath(deathRecipient, 0) }
            }
            Log.i(TAG, "Connected to DIMNaviInteractioncService")
            workerHandler?.post { publisher?.flushNow() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) { remoteBinder = null }
            Log.w(TAG, "Disconnected from DIMNaviInteractioncService")
        }
    }

    fun initialize(context: Context) {
        if (!Kx11DimSettings.isSupported()) return
        val app = context.applicationContext
        synchronized(lock) {
            appContext = app
            if (workerThread == null) {
                workerThread = HandlerThread("diplay-kx11-dim-navi").apply { start() }
                workerHandler = Handler(workerThread!!.looper)
            }
            if (publisher == null) {
                val handler = workerHandler!!
                publisher = Kx11DimNaviPublisher(
                    binderProvider = ::ensureBinder,
                    scheduleDelayed = { delayMs, action -> handler.postDelayed(action, delayMs) },
                    cancelScheduled = { action -> handler.removeCallbacks(action) },
                    onNaviActiveChanged = { active ->
                        naviActive = active
                        appContext?.let { Kx11DimMenuController.onNaviActiveChanged(it, active) }
                        runCatching { clusterNaviListener?.invoke(active) }
                    },
                )
            }
            publisher?.setEnabled(Kx11DimSettings.naviHudEnabled(app))
            if (Kx11DimSettings.naviHudEnabled(app)) {
                bindLocked(app)
            }
            Kx11DimMenuController.warmup(app)
        }
    }

    fun settingChanged(context: Context, enabled: Boolean) {
        if (!Kx11DimSettings.isSupported()) return
        initialize(context)
        val app = context.applicationContext
        workerHandler?.post {
            publisher?.setEnabled(enabled)
            if (enabled) {
                synchronized(lock) { bindLocked(app) }
                publisher?.flushNow()
            }
        }
    }

    fun onFrame(frame: Iap2Frame) {
        if (!Kx11DimSettings.isSupported()) return
        val handler = workerHandler ?: return
        handler.post { publisher?.onFrame(frame) }
    }

    fun clear() {
        val handler = workerHandler ?: return
        handler.post { publisher?.clear() }
    }

    private fun ensureBinder(): IBinder? {
        synchronized(lock) {
            remoteBinder?.takeIf { it.isBinderAlive }?.let { return it }
            remoteBinder = null
            val app = appContext ?: return null
            if (!bound) bindLocked(app)
            return null
        }
    }

    private fun bindLocked(context: Context) {
        if (bound) return
        val intent = Intent().setComponent(
            ComponentName(Kx11DimNaviBinder.PACKAGE_NAME, Kx11DimNaviBinder.SERVICE_CLASS),
        )
        bound = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure {
            Log.w(TAG, "bindService DIMNaviInteractioncService threw: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
        Log.i(TAG, "bindService DIMNaviInteractioncService bound=$bound")
    }
}
