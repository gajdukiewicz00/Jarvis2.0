package org.jarvis.desktop.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pure logic tests for the idle-vs-failure response code classification that
 * keeps Voice WS from reporting a "connected but DEGRADED" contradiction when
 * a session simply ends without any audio ([VoiceWebSocketClient.IDLE_RESPONSE_CODES]).
 */
class VoiceWebSocketClientIdleResponseCodeTest {

    @Test
    @DisplayName("NO_AUDIO_RECEIVED is classified as idle, not a failure")
    fun noAudioReceivedIsIdle() {
        assertTrue(VoiceWebSocketClient.isIdleResponseCode("NO_AUDIO_RECEIVED"))
    }

    @Test
    @DisplayName("STT_UNAVAILABLE is a real failure, not idle")
    fun sttUnavailableIsNotIdle() {
        assertFalse(VoiceWebSocketClient.isIdleResponseCode("STT_UNAVAILABLE"))
    }

    @Test
    @DisplayName("TTS_UNAVAILABLE is a real failure, not idle")
    fun ttsUnavailableIsNotIdle() {
        assertFalse(VoiceWebSocketClient.isIdleResponseCode("TTS_UNAVAILABLE"))
    }

    @Test
    @DisplayName("null and unknown codes are not classified as idle")
    fun unknownAndNullCodesAreNotIdle() {
        assertFalse(VoiceWebSocketClient.isIdleResponseCode(null))
        assertFalse(VoiceWebSocketClient.isIdleResponseCode("VOICE_PROTOCOL_ERROR"))
        assertFalse(VoiceWebSocketClient.isIdleResponseCode(""))
    }
}
