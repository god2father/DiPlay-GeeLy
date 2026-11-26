package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, shadows = [AudioFocusPermanentLossTest.GainTrackingAudioTrack::class])
class AudioFocusPermanentLossTest {

    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val tracks = mutableListOf<AudioTrack>()

    private fun musicAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private fun musicTrack(): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(musicAttributes())
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(48000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(4096)
        .build()
        .also(tracks::add)

    @Before
    fun reset() {
        GainTrackingAudioTrack.gains.clear()
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    @After
    fun cleanup() {
        tracks.forEach(AudioTrack::release)
        tracks.clear()
        GainTrackingAudioTrack.gains.clear()
    }

    @Test
    fun unifiedMediaOutputFiresPermanentLossCallbackOnlyOnPermanentLoss() {
        var permanentLossCount = 0
        val ownershipHistory = mutableListOf<Boolean>()
        val coordinator = AudioFocusCoordinator(
            context = app,
            enabled = true,
            unifiedMediaOutput = true,
            onOwnershipChanged = { ownershipHistory += it },
            onPermanentFocusLoss = { permanentLossCount++ },
        )
        val track = musicTrack()
        coordinator.acquire(track, AudioChannel.MEDIA, musicAttributes())

        // Transient duck (e.g. OEM Amap guidance) must NOT fire permanent loss.
        coordinator.onExternalFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(0, permanentLossCount)

        // Transient loss (e.g. voice assistant / phone) must NOT fire permanent loss.
        coordinator.onExternalFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(0, permanentLossCount)

        // Permanent loss (e.g. OEM NetEase music took over) MUST fire permanent loss once.
        coordinator.onExternalFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1, permanentLossCount)
        assertFalse(ownershipHistory.last())

        // Regaining focus restores ownership without firing permanent loss.
        coordinator.onExternalFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1, permanentLossCount)
        assertTrue(ownershipHistory.last())

        coordinator.close()
    }

    @Test
    fun disablingTransientDuckKeepsMusicAtFullVolumeOnCanDuck() {
        val coordinator = AudioFocusCoordinator(
            context = app,
            enabled = true,
            unifiedMediaOutput = true,
            duckMusicOnTransientDuck = false,
        )
        val track = musicTrack()
        coordinator.acquire(track, AudioChannel.MEDIA, musicAttributes())

        coordinator.onExternalFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        val recorded = GainTrackingAudioTrack.gains[track].orEmpty()
        assertFalse(recorded.any { it < 0.99f })
        assertEquals(1f, recorded.last(), 0.001f)

        coordinator.close()
    }

    @Implements(AudioTrack::class)
    class GainTrackingAudioTrack {
        @RealObject private lateinit var track: AudioTrack

        @Implementation
        fun setVolume(gain: Float): Int {
            gains.getOrPut(track) { mutableListOf() }.add(gain)
            return AudioTrack.SUCCESS
        }

        companion object {
            val gains = linkedMapOf<AudioTrack, MutableList<Float>>()
        }
    }
}
