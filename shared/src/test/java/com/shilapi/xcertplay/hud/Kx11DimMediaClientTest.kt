package com.shilapi.xcertplay.hud

import android.net.Uri
import android.os.Binder
import android.os.Parcel
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class Kx11DimMediaClientTest {

    private data class ReceivedCall(
        val atMs: Long,
        val payload: Kx11PlayMediaPayload,
        val reserved: Int,
    )

    private class FakeDimMediaServiceBinder(private val nowMs: () -> Long) : Binder() {
        val calls = mutableListOf<ReceivedCall>()

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(Kx11DimMediaBinder.DESCRIPTOR)
            assertEquals(Kx11DimMediaBinder.TX_SEND_MESSAGE, code)
            assertEquals(1, data.readInt())
            val payload = Kx11PlayMediaPayload.readFromParcel(data)
            val reserved = data.readInt()
            calls += ReceivedCall(nowMs(), payload, reserved)
            reply?.writeNoException()
            return true
        }
    }

    private class FakeScheduler {
        var nowMs = 5_000L
        private val tasks = linkedMapOf<Runnable, Long>()

        fun schedule(delayMs: Long, action: Runnable) {
            tasks[action] = nowMs + delayMs
        }

        fun cancel(action: Runnable) {
            tasks.remove(action)
        }

        fun pendingCount(): Int = tasks.size

        fun advanceBy(ms: Long) {
            val target = nowMs + ms
            while (true) {
                val next = tasks.entries.filter { it.value <= target }.minByOrNull { it.value } ?: break
                nowMs = next.value
                tasks.remove(next.key)
                next.key.run()
            }
            nowMs = target
        }
    }

    @Test
    fun parcelRoundTripsAll22FieldsWithZeroNullStrings() {
        val uri = Uri.parse("content://com.shihab.diplay.kx11-artwork/artwork/cover_1.jpg")
        val input = Kx11PlayMediaPayload(
            sourceType = Kx11PlayMediaPayload.SOURCE_TYPE_BT,
            uuid = "carplay:123:456",
            title = "Rice Field",
            album = "Capricorn",
            artist = "Jay Chou",
            artwork = uri.toString(),
            artworkUrl = uri,
            durationMs = 223_000L,
            currentProgressMs = 45_000L,
            playbackStatus = Kx11PlayMediaPayload.PLAYBACK_STATUS_PLAYING,
        )
        val parcel = Parcel.obtain()
        try {
            input.writeToParcel(parcel)
            parcel.setDataPosition(0)
            val decoded = Kx11PlayMediaPayload.readFromParcel(parcel)
            assertEquals(input, decoded)
            assertEquals("", decoded.lyricSentence)
            assertEquals("", decoded.radioFrequency)
            assertEquals("", decoded.mediaPath)
            assertEquals(uri, decoded.artworkUrl)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun enforcesMin1050msSpacingHeartbeatsWhilePlayingAndStopsCleanlyOnPause() {
        val clock = FakeScheduler()
        val binder = FakeDimMediaServiceBinder { clock.nowMs }
        val publisher = Kx11DimMediaPublisher(
            binderProvider = { binder },
            scheduleDelayed = clock::schedule,
            cancelScheduled = clock::cancel,
            elapsedRealtimeMs = { clock.nowMs },
        )

        val track = CarPlayNowPlaying(
            title = "Mojito",
            artist = "Jay Chou",
            album = "Greatest Works",
            durationMillis = 185_000L,
            elapsedMillis = 10_000L,
            playing = true,
        )
        // Initial track arrives without artwork -> emits immediately
        publisher.onMediaUpdated(track, elapsedUpdatedAtMs = clock.nowMs, artworkUri = null, mediaAudioActive = true)
        assertEquals(1, binder.calls.size)
        assertEquals(Kx11PlayMediaPayload.SOURCE_TYPE_BT, binder.calls[0].payload.sourceType)
        assertEquals(Kx11PlayMediaPayload.PLAYBACK_STATUS_PLAYING, binder.calls[0].payload.playbackStatus)
        assertEquals(10_000L, binder.calls[0].payload.currentProgressMs)

        // Cover arrives 150ms later -> MUST wait until >= 1050ms after first call to avoid OnceExecute(1000ms) starvation
        clock.advanceBy(150L)
        val coverUri = Uri.parse("content://com.shihab.diplay.kx11-artwork/artwork/cover_1.jpg")
        publisher.onMediaUpdated(track, elapsedUpdatedAtMs = 5_000L, artworkUri = coverUri, mediaAudioActive = true)
        assertEquals(1, binder.calls.size)

        clock.advanceBy(899L) // total 1049ms -> still throttled
        assertEquals(1, binder.calls.size)

        clock.advanceBy(1L) // total 1050ms -> fires!
        assertEquals(2, binder.calls.size)
        assertEquals(coverUri, binder.calls[1].payload.artworkUrl)
        assertTrue(binder.calls[1].atMs - binder.calls[0].atMs >= Kx11DimMediaPublisher.MIN_SEND_INTERVAL_MS)

        // Heartbeat fires 1800ms later with extrapolated progress
        clock.advanceBy(1_800L)
        assertEquals(3, binder.calls.size)
        assertEquals(12_850L, binder.calls[2].payload.currentProgressMs)

        // Pause -> emits one PAUSED(0) frame and cancels all timers so stock music apps are never overwritten
        clock.advanceBy(1_200L)
        val paused = track.copy(playing = false, elapsedMillis = 14_050L)
        publisher.onMediaUpdated(paused, elapsedUpdatedAtMs = clock.nowMs, artworkUri = coverUri, mediaAudioActive = false)
        assertEquals(4, binder.calls.size)
        assertEquals(Kx11PlayMediaPayload.PLAYBACK_STATUS_PAUSED, binder.calls[3].payload.playbackStatus)
        assertEquals(0, clock.pendingCount())

        // No further emissions while paused
        clock.advanceBy(10_000L)
        assertEquals(4, binder.calls.size)
    }

    @Test
    fun reassertsClusterMedia1600msAfterBluetoothDisconnectWhilePlaying() {
        val clock = FakeScheduler()
        val binder = FakeDimMediaServiceBinder { clock.nowMs }
        val publisher = Kx11DimMediaPublisher(
            binderProvider = { binder },
            scheduleDelayed = clock::schedule,
            cancelScheduled = clock::cancel,
            elapsedRealtimeMs = { clock.nowMs },
            progressHeartbeatMs = 10_000L, // isolate BT recovery timer
        )

        val track = CarPlayNowPlaying(
            title = "Sunny Day",
            artist = "Jay Chou",
            durationMillis = 269_000L,
            elapsedMillis = 20_000L,
            playing = true,
        )
        publisher.onMediaUpdated(track, elapsedUpdatedAtMs = clock.nowMs, artworkUri = null, mediaAudioActive = true)
        assertEquals(1, binder.calls.size)

        // Stock BT A2DP disconnects right after CarPlay starts
        clock.advanceBy(100L)
        publisher.onBluetoothDisconnected()
        clock.advanceBy(1_599L)
        assertEquals(1, binder.calls.size)

        clock.advanceBy(1L) // 1600ms elapsed (> server's 1200ms REMOVE_MEDIA_MSG)
        assertEquals(2, binder.calls.size)
        assertEquals("Sunny Day", binder.calls[1].payload.title)
        assertEquals(Kx11PlayMediaPayload.PLAYBACK_STATUS_PLAYING, binder.calls[1].payload.playbackStatus)

        // Session end sends single PAUSED frame
        clock.advanceBy(2_000L)
        publisher.onSessionEnded()
        assertEquals(3, binder.calls.size)
        assertEquals(Kx11PlayMediaPayload.PLAYBACK_STATUS_PAUSED, binder.calls[2].payload.playbackStatus)
    }

    @Test
    fun preservesPendingForceAcrossThrottleAndFlushesPausedFirstFrameOnLateBind() {
        val clock = FakeScheduler()
        var connectedBinder: FakeDimMediaServiceBinder? = null
        val binder = FakeDimMediaServiceBinder { clock.nowMs }
        val publisher = Kx11DimMediaPublisher(
            binderProvider = { connectedBinder },
            scheduleDelayed = clock::schedule,
            cancelScheduled = clock::cancel,
            elapsedRealtimeMs = { clock.nowMs },
            progressHeartbeatMs = 10_000L,
        )

        // 1. Paused initial track arrives BEFORE bindService finishes -> must stay pending and flush once bound
        val pausedTrack = CarPlayNowPlaying(
            title = "Nocturne",
            artist = "Jay Chou",
            durationMillis = 0L,
            elapsedMillis = 0L,
            playing = false,
        )
        publisher.onMediaUpdated(pausedTrack, elapsedUpdatedAtMs = clock.nowMs, artworkUri = null, mediaAudioActive = false)
        assertEquals(0, binder.calls.size)

        connectedBinder = binder
        publisher.flushIfActive()
        assertEquals(1, binder.calls.size)
        assertEquals("Nocturne", binder.calls[0].payload.title)
        assertEquals(Kx11PlayMediaPayload.PLAYBACK_STATUS_PAUSED, binder.calls[0].payload.playbackStatus)

        // 2. Playing track with progress already at duration cap so candidate == lastSentPayload:
        // forced BT recovery hits throttle window (<1050ms) and MUST preserve force=true when
        // flushTask fires later so the identical payload is still re-emitted.
        clock.advanceBy(1_200L)
        val streamTrack = pausedTrack.copy(playing = true, durationMillis = 10_000L, elapsedMillis = 10_000L)
        publisher.onMediaUpdated(streamTrack, elapsedUpdatedAtMs = clock.nowMs, artworkUri = null, mediaAudioActive = true)
        assertEquals(2, binder.calls.size)

        clock.advanceBy(100L)
        publisher.flushIfActive() // throttled inside 1050ms window with identical candidate payload
        assertEquals(2, binder.calls.size)

        clock.advanceBy(950L) // reaches 1050ms -> pendingForce MUST still trigger emit!
        assertEquals(3, binder.calls.size)
        assertEquals(10_000L, binder.calls[2].payload.currentProgressMs)
    }
}
