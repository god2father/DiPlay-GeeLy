package com.shilapi.xcertplay.media

import android.media.AudioManager

// carlito | FaceTime uses the same communication resources as a telephone call.
internal fun isPhoneAudio(type: String) = type.equals("telephony", true) || type.equals("facetime", true)

// carlito | CarPlay default carries guidance; alert/compatibility remain on the shared media output.
internal fun isVehicleNavigationAudio(type: String): Boolean =
    type.equals("default", true) || type.equals("guidance", true) || type.equals("navigation", true)

/**
 * Channel selection for unified automotive media routing.
 *
 * When [ignoreNavigationDuck] is enabled (e.g. KX11_A2), CarPlay `alert` and `compatibility`
 * short prompts route as [AudioChannel.NAVIGATION] so they play on the navigation/headrest bus
 * without grabbing `RINGTONE`/speech priority or muting background music to 0f.
 * Calls (`telephony`/`facetime`), incoming ringtones (`ringtone`/`ring`), and Siri
 * (`speechrecognition`) retain full interruption semantics.
 */
internal fun selectUnifiedAudioChannel(
    audioType: String,
    ignoreNavigationDuck: Boolean,
): AudioChannelSelection {
    if (audioType.equals("compatibility", true)) {
        return if (ignoreNavigationDuck) {
            AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH)
        } else {
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.SPEECH)
        }
    }
    if (isVehicleNavigationAudio(audioType)) {
        return AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH)
    }
    return when (audioType.lowercase()) {
        "telephony", "facetime" -> AudioChannelSelection(AudioChannel.PHONE, AudioContentType.SPEECH)
        "speechrecognition" -> AudioChannelSelection(AudioChannel.ASSISTANT, AudioContentType.SPEECH)
        "ringtone", "ring" -> AudioChannelSelection(AudioChannel.RINGTONE, AudioContentType.SPEECH)
        "alert" -> if (ignoreNavigationDuck) {
            AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH)
        } else {
            AudioChannelSelection(AudioChannel.RINGTONE, AudioContentType.SPEECH)
        }
        else -> AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
    }
}

internal enum class AudioChannelMappingMode {
    MOBILE_COMPATIBLE,
    AUTOMOTIVE_BUS,
}

internal enum class AudioChannel {
    MEDIA,
    PHONE,
    ASSISTANT,
    NAVIGATION,
    RINGTONE,
}

internal enum class AudioContentType {
    MUSIC,
    SPEECH,
}

internal data class AudioChannelSelection(
    val channel: AudioChannel,
    val contentType: AudioContentType,
    val streamType: Int = AudioManager.STREAM_MUSIC,
)

/**
 * Maps CarPlay stream metadata to Android audio routing values.
 *
 * Each selection carries the [AudioChannel] / [AudioContentType] pair used for usage-based
 * AudioAttributes, plus the stream type used when the stream-type AudioTrack path is active.
 * Guidance resolves to [AudioChannel.NAVIGATION]. Automotive routing keeps compatibility
 * audio on the media bus, so it receives the configured media buffer and output route.
 */
internal object AudioChannelMapper {
    const val STREAM_TYPE_MAIN_HIGH_AUDIO = 102
    const val DEFAULT_NAVIGATION_STREAM_TYPE = 14

    fun map(
        audioType: String,
        payloadType: Int,
        mode: AudioChannelMappingMode,
        navigationStreamType: Int = DEFAULT_NAVIGATION_STREAM_TYPE,
    ): AudioChannelSelection {
        val normalized = audioType.lowercase()
        return when (mode) {
            AudioChannelMappingMode.MOBILE_COMPATIBLE -> mapMobileCompatible(normalized, payloadType, navigationStreamType)
            AudioChannelMappingMode.AUTOMOTIVE_BUS -> mapAutomotiveBus(normalized, payloadType, navigationStreamType)
        }
    }

    fun usesNavigationStream(audioType: String, payloadType: Int, advanced: Boolean): Boolean =
        !advanced && map(audioType, payloadType, AudioChannelMappingMode.MOBILE_COMPATIBLE).channel == AudioChannel.NAVIGATION

    /**
     * When the car Bluetooth radio is off there is no SCO, so a CarPlay telephony stream has to
     * play on the media speaker instead of the phone usage.
     */
    fun playCallOnSpeaker(selection: AudioChannelSelection): AudioChannelSelection =
        if (selection.channel == AudioChannel.PHONE) {
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.SPEECH, selection.streamType)
        } else {
            selection
        }

    private fun mapMobileCompatible(
        audioType: String,
        payloadType: Int,
        navigationStreamType: Int,
    ): AudioChannelSelection = when (audioType) {
        "telephony", "facetime" -> AudioChannelSelection(AudioChannel.PHONE, AudioContentType.SPEECH)
        "ringtone", "ring" -> AudioChannelSelection(AudioChannel.RINGTONE, AudioContentType.SPEECH)
        "speechrecognition" ->
            AudioChannelSelection(AudioChannel.ASSISTANT, AudioContentType.SPEECH)
        "media" -> AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        "default", "alert", "compatibility" ->
            AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, navigationStreamType)
        else -> mainHighAudioOrNavigation(payloadType, navigationStreamType)
    }

    private fun mapAutomotiveBus(
        audioType: String,
        payloadType: Int,
        navigationStreamType: Int,
    ): AudioChannelSelection = when (audioType) {
        "telephony", "facetime" -> AudioChannelSelection(AudioChannel.PHONE, AudioContentType.SPEECH)
        "ringtone", "ring" -> AudioChannelSelection(AudioChannel.RINGTONE, AudioContentType.SPEECH)
        "speechrecognition" ->
            AudioChannelSelection(AudioChannel.ASSISTANT, AudioContentType.SPEECH)
        "media", "compatibility" ->
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        "default", "alert" -> AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, navigationStreamType)
        else -> mainHighAudioOrNavigation(payloadType, navigationStreamType)
    }

    private fun mainHighAudioOrNavigation(payloadType: Int, navigationStreamType: Int): AudioChannelSelection =
        if (payloadType == STREAM_TYPE_MAIN_HIGH_AUDIO) {
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        } else {
            AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, navigationStreamType)
        }
}
