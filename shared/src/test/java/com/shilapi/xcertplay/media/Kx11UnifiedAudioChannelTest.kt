package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class Kx11UnifiedAudioChannelTest {

    @Test
    fun ignoreNavigationDuckRoutesAlertAndCompatibilityToNavigationWithoutBreakingCallsOrSiri() {
        // Navigation, alert, and compatibility prompts stay on NAVIGATION (no RINGTONE/speech mute).
        for (promptType in listOf("default", "guidance", "navigation", "alert", "ALERT", "compatibility")) {
            val selected = selectUnifiedAudioChannel(promptType, ignoreNavigationDuck = true)
            assertEquals("Expected NAVIGATION for $promptType", AudioChannel.NAVIGATION, selected.channel)
            assertEquals(AudioContentType.SPEECH, selected.contentType)
        }

        // Calls, incoming ringtones, and Siri keep full interruption semantics.
        assertEquals(AudioChannel.PHONE, selectUnifiedAudioChannel("telephony", ignoreNavigationDuck = true).channel)
        assertEquals(AudioChannel.PHONE, selectUnifiedAudioChannel("facetime", ignoreNavigationDuck = true).channel)
        assertEquals(AudioChannel.RINGTONE, selectUnifiedAudioChannel("ringtone", ignoreNavigationDuck = true).channel)
        assertEquals(AudioChannel.RINGTONE, selectUnifiedAudioChannel("ring", ignoreNavigationDuck = true).channel)
        assertEquals(AudioChannel.ASSISTANT, selectUnifiedAudioChannel("speechrecognition", ignoreNavigationDuck = true).channel)
        assertEquals(AudioChannel.MEDIA, selectUnifiedAudioChannel("media", ignoreNavigationDuck = true).channel)
    }

    @Test
    fun defaultUnifiedRoutingPreservesUpstreamBehaviorWhenIgnoreDuckDisabled() {
        assertEquals(AudioChannel.RINGTONE, selectUnifiedAudioChannel("alert", ignoreNavigationDuck = false).channel)
        val compat = selectUnifiedAudioChannel("compatibility", ignoreNavigationDuck = false)
        assertEquals(AudioChannel.MEDIA, compat.channel)
        assertEquals(AudioContentType.SPEECH, compat.contentType)
        assertEquals(AudioChannel.NAVIGATION, selectUnifiedAudioChannel("default", ignoreNavigationDuck = false).channel)
    }
}
