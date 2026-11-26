package com.shilapi.xcertplay.hud

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/**
 * Exact 22-field wire layout for `com.autolink.adapterbinder.PlayMediaInfo`. Every String field
 * defaults to `""` (never `null`, which causes the cluster service to emit literal `"-999"`).
 */
data class Kx11PlayMediaPayload(
    val sourceType: Int = SOURCE_TYPE_BT,
    val uuid: String = "",
    val muteState: Int = 0,
    val title: String = "",
    val album: String = "",
    val artist: String = "",
    val artwork: String = "",
    val artworkUrl: Uri? = null,
    val nextArtwork: String = "",
    val lyricSentence: String = "",
    val lyricContent: String = "",
    val lyricUrl: String = "",
    val durationMs: Long = 0L,
    val currentProgressMs: Long = 0L,
    val favoriteState: Int = 0,
    val loopMode: Int = 0,
    val mediaPath: String = "",
    val playbackStatus: Int = PLAYBACK_STATUS_PAUSED,
    val playingPosition: Int = 0,
    val radioFrequency: String = "",
    val radioMode: Int = 0,
    val radioStationName: String = "",
) {
    fun writeToParcel(dest: Parcel) {
        dest.writeInt(sourceType)
        dest.writeString(uuid)
        dest.writeInt(muteState)
        dest.writeString(title)
        dest.writeString(album)
        dest.writeString(artist)
        dest.writeString(artwork)
        dest.writeParcelable(artworkUrl, 0)
        dest.writeString(nextArtwork)
        dest.writeString(lyricSentence)
        dest.writeString(lyricContent)
        dest.writeString(lyricUrl)
        dest.writeLong(durationMs)
        dest.writeLong(currentProgressMs)
        dest.writeInt(favoriteState)
        dest.writeInt(loopMode)
        dest.writeString(mediaPath)
        dest.writeInt(playbackStatus)
        dest.writeInt(playingPosition)
        dest.writeString(radioFrequency)
        dest.writeInt(radioMode)
        dest.writeString(radioStationName)
    }

    companion object {
        const val SOURCE_TYPE_BT = 2
        const val SOURCE_TYPE_ONLINE = 6
        const val PLAYBACK_STATUS_PAUSED = 0
        const val PLAYBACK_STATUS_PLAYING = 1

        fun readFromParcel(src: Parcel): Kx11PlayMediaPayload = Kx11PlayMediaPayload(
            sourceType = src.readInt(),
            uuid = src.readString().orEmpty(),
            muteState = src.readInt(),
            title = src.readString().orEmpty(),
            album = src.readString().orEmpty(),
            artist = src.readString().orEmpty(),
            artwork = src.readString().orEmpty(),
            artworkUrl = @Suppress("DEPRECATION") src.readParcelable(Uri::class.java.classLoader),
            nextArtwork = src.readString().orEmpty(),
            lyricSentence = src.readString().orEmpty(),
            lyricContent = src.readString().orEmpty(),
            lyricUrl = src.readString().orEmpty(),
            durationMs = src.readLong(),
            currentProgressMs = src.readLong(),
            favoriteState = src.readInt(),
            loopMode = src.readInt(),
            mediaPath = src.readString().orEmpty(),
            playbackStatus = src.readInt(),
            playingPosition = src.readInt(),
            radioFrequency = src.readString().orEmpty(),
            radioMode = src.readInt(),
            radioStationName = src.readString().orEmpty(),
        )
    }
}

/**
 * Low-level Binder proxy speaking directly to KX11_A2's exported
 * `com.autolink.diminteraction/.DIMMediaInteractioncService` with zero OEM JAR dependencies.
 */
internal class Kx11DimMediaBinder(private val remote: IBinder) {
    fun sendMessage(payload: Kx11PlayMediaPayload, reserved: Int = 0) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(1)
            payload.writeToParcel(data)
            data.writeInt(reserved)
            remote.transact(TX_SEND_MESSAGE, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    companion object {
        const val PACKAGE_NAME = "com.autolink.diminteraction"
        const val SERVICE_CLASS = "com.autolink.diminteraction.DIMMediaInteractioncService"
        const val DESCRIPTOR = "com.autolink.adapterbinder.IDimMediaInteractioncService"
        const val TX_SEND_MESSAGE = 1
    }
}

/**
 * Stateful KX11_A2 OEM cluster media publisher.
 *
 * Designed specifically around the verified quirks of `DIMMediaInteractioncService`:
 * 1. Server `OnceExecute` uses `delay = 1000L`: sending more frequently than every 1000ms starves
 *    execution forever. We enforce a strict minimum `>= 1050ms` spacing between `sendMessage` calls.
 * 2. While playing, progress heartbeats run at `1800ms` (> 1000ms). Once paused or ended, we emit
 *    a single `PLAYBACK_STATUS_PAUSED (0)` frame and stop all timers so stock NetEase/QQ Music is
 *    never overwritten.
 * 3. When using `SOURCE_TYPE_BT (2)` (required for cross-process `ContentResolver.openFileDescriptor`
 *    artwork delivery), stock Bluetooth A2DP disconnect triggers a delayed `1200ms` remove-source
 *    message on the server; `onBluetoothDisconnected()` schedules a `1600ms` re-assert frame if
 *    CarPlay is actively playing.
 */
internal class Kx11DimMediaPublisher(
    private val binderProvider: () -> IBinder?,
    private val scheduleDelayed: (delayMs: Long, action: Runnable) -> Unit,
    private val cancelScheduled: (action: Runnable) -> Unit,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val minSendIntervalMs: Long = MIN_SEND_INTERVAL_MS,
    private val progressHeartbeatMs: Long = PROGRESS_HEARTBEAT_MS,
    private val btDisconnectRecoveryMs: Long = BT_DISCONNECT_RECOVERY_MS,
) {
    private val lock = Any()
    private var enabled = true
    private var latestInfo = CarPlayNowPlaying()
    private var latestArtworkUri: Uri? = null
    private var latestPlaying = false
    private var baseElapsedMs = 0L
    private var baseUpdatedAtMs = 0L
    private var hasEmittedAny = false
    private var lastSentMs = Long.MIN_VALUE
    private var lastSentPayload: Kx11PlayMediaPayload? = null
    private var pendingFlush = false
    private var pendingForce = false

    private val flushTask = Runnable { emitPending(force = false) }
    private val heartbeatTask = Runnable { onHeartbeat() }
    private val btRecoveryTask = Runnable { emitPending(force = true) }

    fun setEnabled(next: Boolean) {
        val shouldStop = synchronized(lock) {
            if (enabled == next) return
            enabled = next
            !next
        }
        if (shouldStop) onSessionEnded()
    }

    fun onMediaUpdated(
        info: CarPlayNowPlaying,
        elapsedUpdatedAtMs: Long,
        artworkUri: Uri?,
        mediaAudioActive: Boolean,
    ) {
        val playing = if (info.title != null || info.elapsedMillis != null) info.playing else mediaAudioActive
        val hasMetadata = !info.title.isNullOrBlank() || !info.artist.isNullOrBlank() || !info.album.isNullOrBlank()
        val delayToFlush: Long
        synchronized(lock) {
            if (!enabled) return
            if (!hasMetadata && !hasEmittedAny) return
            val previousPlaying = latestPlaying
            val metadataOrArtChanged =
                info.title != latestInfo.title ||
                    info.artist != latestInfo.artist ||
                    info.album != latestInfo.album ||
                    info.durationMillis != latestInfo.durationMillis ||
                    artworkUri != latestArtworkUri ||
                    playing != previousPlaying

            latestInfo = info
            latestArtworkUri = artworkUri
            latestPlaying = playing
            baseElapsedMs = (info.elapsedMillis ?: 0L).coerceAtLeast(0L)
            baseUpdatedAtMs = if (elapsedUpdatedAtMs > 0L) elapsedUpdatedAtMs else elapsedRealtimeMs()

            if (!playing) {
                cancelScheduled(heartbeatTask)
                cancelScheduled(btRecoveryTask)
            }
            if (!metadataOrArtChanged && hasEmittedAny) {
                return
            }
            val now = elapsedRealtimeMs()
            val sinceLast = if (lastSentMs == Long.MIN_VALUE) minSendIntervalMs else (now - lastSentMs).coerceAtLeast(0L)
            delayToFlush = if (sinceLast >= minSendIntervalMs) 0L else (minSendIntervalMs - sinceLast)
            pendingFlush = true
        }
        if (delayToFlush == 0L) {
            cancelScheduled(flushTask)
            emitPending(force = false)
        } else {
            cancelScheduled(flushTask)
            scheduleDelayed(delayToFlush, flushTask)
        }
    }

    fun onBluetoothDisconnected() {
        val shouldRecover = synchronized(lock) { enabled && latestPlaying && hasEmittedAny }
        if (!shouldRecover) return
        cancelScheduled(btRecoveryTask)
        scheduleDelayed(btDisconnectRecoveryMs, btRecoveryTask)
    }

    fun onSessionEnded() {
        cancelScheduled(flushTask)
        cancelScheduled(heartbeatTask)
        cancelScheduled(btRecoveryTask)
        val pausedPayload = synchronized(lock) {
            latestPlaying = false
            pendingFlush = false
            pendingForce = false
            val prev = lastSentPayload
            latestInfo = CarPlayNowPlaying()
            latestArtworkUri = null
            baseElapsedMs = 0L
            baseUpdatedAtMs = 0L
            if (!hasEmittedAny || prev == null || prev.playbackStatus == Kx11PlayMediaPayload.PLAYBACK_STATUS_PAUSED) {
                hasEmittedAny = false
                lastSentPayload = null
                return
            }
            hasEmittedAny = false
            val paused = prev.copy(playbackStatus = Kx11PlayMediaPayload.PLAYBACK_STATUS_PAUSED)
            lastSentPayload = null
            paused
        }
        val remote = binderProvider() ?: return
        runCatching {
            Kx11DimMediaBinder(remote).sendMessage(pausedPayload, 0)
            synchronized(lock) { lastSentMs = elapsedRealtimeMs() }
        }.onFailure {
            Log.w(TAG, "pause-on-end failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    fun flushIfActive() {
        val active = synchronized(lock) {
            val hasMetadata = !latestInfo.title.isNullOrBlank() ||
                !latestInfo.artist.isNullOrBlank() ||
                !latestInfo.album.isNullOrBlank()
            enabled && (latestPlaying || pendingFlush) && hasMetadata
        }
        if (active) emitPending(force = true)
    }

    private fun onHeartbeat() {
        val active = synchronized(lock) { enabled && latestPlaying }
        if (!active) return
        emitPending(force = false)
    }

    private fun emitPending(force: Boolean) {
        val payload: Kx11PlayMediaPayload
        val keepHeartbeat: Boolean
        synchronized(lock) {
            if (!enabled) return
            if (force) pendingForce = true
            val effectiveForce = pendingForce
            val now = elapsedRealtimeMs()
            val sinceLast = if (lastSentMs == Long.MIN_VALUE) minSendIntervalMs else (now - lastSentMs).coerceAtLeast(0L)
            if (sinceLast < minSendIntervalMs) {
                pendingFlush = true
                cancelScheduled(flushTask)
                scheduleDelayed(minSendIntervalMs - sinceLast, flushTask)
                return
            }
            val duration = (latestInfo.durationMillis ?: 0L).coerceAtLeast(0L)
            val progress = computeProgressLocked(now, duration)
            val uri = latestArtworkUri
            val candidate = Kx11PlayMediaPayload(
                sourceType = Kx11PlayMediaPayload.SOURCE_TYPE_BT,
                uuid = buildTrackUuid(latestInfo),
                muteState = 0,
                title = latestInfo.title.orEmpty(),
                album = latestInfo.album.orEmpty(),
                artist = latestInfo.artist.orEmpty(),
                artwork = uri?.toString().orEmpty(),
                artworkUrl = uri,
                durationMs = duration,
                currentProgressMs = progress,
                playbackStatus = if (latestPlaying) Kx11PlayMediaPayload.PLAYBACK_STATUS_PLAYING else Kx11PlayMediaPayload.PLAYBACK_STATUS_PAUSED,
            )
            if (!effectiveForce && candidate == lastSentPayload) {
                pendingFlush = false
                pendingForce = false
                if (latestPlaying) scheduleNextHeartbeat()
                return
            }
            payload = candidate
            keepHeartbeat = latestPlaying
        }

        val remote = binderProvider()
        if (remote == null) {
            synchronized(lock) { pendingFlush = true }
            return
        }
        runCatching {
            Kx11DimMediaBinder(remote).sendMessage(payload, 0)
            synchronized(lock) {
                hasEmittedAny = true
                pendingFlush = false
                pendingForce = false
                lastSentMs = elapsedRealtimeMs()
                lastSentPayload = payload
            }
        }.onFailure {
            Log.w(TAG, "sendMessage failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        cancelScheduled(heartbeatTask)
        if (keepHeartbeat) scheduleNextHeartbeat()
    }

    private fun scheduleNextHeartbeat() {
        scheduleDelayed(progressHeartbeatMs, heartbeatTask)
    }

    private fun computeProgressLocked(nowMs: Long, durationMs: Long): Long {
        val delta = if (latestPlaying) (nowMs - baseUpdatedAtMs).coerceAtLeast(0L) else 0L
        val raw = (baseElapsedMs + delta).coerceAtLeast(0L)
        return if (durationMs > 0L) raw.coerceAtMost(durationMs) else raw
    }

    private fun buildTrackUuid(info: CarPlayNowPlaying): String {
        val t = info.title.orEmpty()
        val a = info.artist.orEmpty()
        if (t.isEmpty() && a.isEmpty()) return ""
        return "carplay:${t.hashCode()}:${a.hashCode()}"
    }

    companion object {
        private const val TAG = "DiPlay-KX11DimMedia"
        const val MIN_SEND_INTERVAL_MS = 1_050L
        const val PROGRESS_HEARTBEAT_MS = 1_800L
        const val BT_DISCONNECT_RECOVERY_MS = 1_600L
    }
}

/** Process-wide Android lifecycle host for KX11_A2 OEM cluster media output. */
object Kx11DimMediaBridge {
    private const val TAG = "DiPlay-KX11DimMedia"
    private const val ACTION_A2DP_SINK_STATE = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
    private const val ACTION_BT_ADAPTER_STATE = "android.bluetooth.adapter.action.STATE_CHANGED"

    private val lock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var remoteBinder: IBinder? = null
    private var bound = false
    private var btReceiverRegistered = false
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var publisher: Kx11DimMediaPublisher? = null

    private val deathRecipient = IBinder.DeathRecipient {
        synchronized(lock) {
            remoteBinder = null
            bound = false
        }
        Log.w(TAG, "DIMMediaInteractioncService binder died; will rebind on next update")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            synchronized(lock) {
                remoteBinder = service
                runCatching { service?.linkToDeath(deathRecipient, 0) }
            }
            Log.i(TAG, "Connected to DIMMediaInteractioncService")
            workerHandler?.post { publisher?.flushIfActive() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) { remoteBinder = null }
            Log.w(TAG, "Disconnected from DIMMediaInteractioncService")
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val disconnected = when (action) {
                ACTION_A2DP_SINK_STATE -> intent.getIntExtra("android.bluetooth.profile.extra.STATE", -1) == 0
                ACTION_BT_ADAPTER_STATE -> intent.getIntExtra("android.bluetooth.adapter.extra.STATE", -1) == 10
                else -> false
            }
            if (disconnected) {
                workerHandler?.post { publisher?.onBluetoothDisconnected() }
            }
        }
    }

    fun initialize(context: Context) {
        if (!Kx11DimSettings.isSupported()) return
        val app = context.applicationContext
        synchronized(lock) {
            appContext = app
            if (workerThread == null) {
                workerThread = HandlerThread("diplay-kx11-dim-media").apply { start() }
                workerHandler = Handler(workerThread!!.looper)
            }
            if (publisher == null) {
                val handler = workerHandler!!
                publisher = Kx11DimMediaPublisher(
                    binderProvider = ::ensureBinder,
                    scheduleDelayed = { delayMs, action -> handler.postDelayed(action, delayMs) },
                    cancelScheduled = { action -> handler.removeCallbacks(action) },
                )
            }
            if (!btReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(ACTION_A2DP_SINK_STATE)
                    addAction(ACTION_BT_ADAPTER_STATE)
                }
                runCatching { app.registerReceiver(btReceiver, filter) }
                    .onSuccess { btReceiverRegistered = true }
            }
            publisher?.setEnabled(Kx11DimSettings.clusterMediaEnabled(app))
            if (Kx11DimSettings.clusterMediaEnabled(app)) {
                bindLocked(app)
            }
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
                publisher?.flushIfActive()
            }
        }
    }

    fun onMediaUpdated(
        info: CarPlayNowPlaying,
        elapsedUpdatedAtMs: Long,
        artworkUri: Uri?,
        mediaAudioActive: Boolean,
    ) {
        if (!Kx11DimSettings.isSupported()) return
        val handler = workerHandler ?: return
        handler.post {
            publisher?.onMediaUpdated(info, elapsedUpdatedAtMs, artworkUri, mediaAudioActive)
        }
    }

    fun onSessionEnded() {
        val handler = workerHandler ?: return
        handler.post { publisher?.onSessionEnded() }
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
            ComponentName(Kx11DimMediaBinder.PACKAGE_NAME, Kx11DimMediaBinder.SERVICE_CLASS),
        )
        bound = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure {
            Log.w(TAG, "bindService DIMMediaInteractioncService threw: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
        Log.i(TAG, "bindService DIMMediaInteractioncService bound=$bound")
    }
}
