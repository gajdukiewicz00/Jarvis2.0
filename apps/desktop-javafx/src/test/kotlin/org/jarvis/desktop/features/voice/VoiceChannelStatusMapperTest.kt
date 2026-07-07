package org.jarvis.desktop.features.voice

import org.jarvis.desktop.features.status.StatusLevel
import org.jarvis.desktop.model.VoiceRuntimeState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

class VoiceChannelStatusMapperTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun baseState(
        connectionPhase: VoiceRuntimeState.ConnectionPhase = VoiceRuntimeState.ConnectionPhase.CONNECTED,
        sttAvailable: Boolean = true,
        ttsAvailable: Boolean = true,
        inputDevice: VoiceRuntimeState.AudioDeviceInfo? = VoiceRuntimeState.AudioDeviceInfo("Mic", available = true),
        lastError: String? = null
    ): VoiceRuntimeState = VoiceRuntimeState.disconnected(now).copy(
        connectionPhase = connectionPhase,
        sttAvailable = sttAvailable,
        ttsAvailable = ttsAvailable,
        inputDevice = inputDevice,
        lastError = lastError
    )

    @Test
    @DisplayName("connected + mic + STT + TTS all healthy maps to all-UP")
    fun healthyStateMapsToUp() {
        val status = VoiceChannelStatusMapper.map(baseState())

        assertEquals(StatusLevel.UP, status.connection)
        assertEquals(StatusLevel.UP, status.mic)
        assertEquals(StatusLevel.UP, status.stt)
        assertEquals(StatusLevel.UP, status.tts)
    }

    @Test
    @DisplayName("a NO_AUDIO_RECEIVED-shaped lastError does not downgrade a healthy connection")
    fun idleErrorTextDoesNotCauseDegradedStatus() {
        // Simulates exactly what would happen if a NO_AUDIO_RECEIVED response
        // were (incorrectly) forwarded into lastError: connection/stt/tts are
        // still healthy, only the free-text error is set.
        val status = VoiceChannelStatusMapper.map(
            baseState(lastError = "Voice session ended before any audio was received.")
        )

        assertEquals(StatusLevel.UP, status.connection, "idle lastError text must not degrade connection")
        assertEquals(StatusLevel.UP, status.stt, "idle lastError text must not degrade STT")
        assertEquals(StatusLevel.UP, status.tts, "idle lastError text must not degrade TTS")
        assertNotEquals(StatusLevel.DEGRADED, status.connection)
        assertNotEquals(StatusLevel.DOWN, status.connection)
    }

    @Test
    @DisplayName("STT reported unavailable degrades only the STT chip, not the connection")
    fun sttUnavailableIsIsolatedDegradation() {
        val status = VoiceChannelStatusMapper.map(baseState(sttAvailable = false))

        assertEquals(StatusLevel.UP, status.connection)
        assertEquals(StatusLevel.DEGRADED, status.stt)
        assertEquals(StatusLevel.UP, status.tts)
    }

    @Test
    @DisplayName("TTS reported unavailable degrades only the TTS chip, not the connection")
    fun ttsUnavailableIsIsolatedDegradation() {
        val status = VoiceChannelStatusMapper.map(baseState(ttsAvailable = false))

        assertEquals(StatusLevel.UP, status.connection)
        assertEquals(StatusLevel.UP, status.stt)
        assertEquals(StatusLevel.DEGRADED, status.tts)
    }

    @Test
    @DisplayName("no input device maps mic to UNAVAILABLE")
    fun missingMicIsUnavailable() {
        val status = VoiceChannelStatusMapper.map(baseState(inputDevice = null))

        assertEquals(StatusLevel.UNAVAILABLE, status.mic)
    }

    @Test
    @DisplayName("a present but unavailable input device maps mic to DEGRADED")
    fun unavailableMicDeviceIsDegraded() {
        val status = VoiceChannelStatusMapper.map(
            baseState(inputDevice = VoiceRuntimeState.AudioDeviceInfo("Mic", available = false))
        )

        assertEquals(StatusLevel.DEGRADED, status.mic)
    }

    @Test
    @DisplayName("disconnected connection phase maps to DOWN")
    fun disconnectedMapsToDown() {
        val status = VoiceChannelStatusMapper.map(
            baseState(connectionPhase = VoiceRuntimeState.ConnectionPhase.DISCONNECTED)
        )

        assertEquals(StatusLevel.DOWN, status.connection)
    }

    @Test
    @DisplayName("failed connection phase maps to DOWN")
    fun failedMapsToDown() {
        val status = VoiceChannelStatusMapper.map(
            baseState(connectionPhase = VoiceRuntimeState.ConnectionPhase.FAILED)
        )

        assertEquals(StatusLevel.DOWN, status.connection)
    }

    @Test
    @DisplayName("connecting phase maps to UNKNOWN, not DEGRADED")
    fun connectingMapsToUnknown() {
        val status = VoiceChannelStatusMapper.map(
            baseState(connectionPhase = VoiceRuntimeState.ConnectionPhase.CONNECTING)
        )

        assertEquals(StatusLevel.UNKNOWN, status.connection)
    }

    @Test
    @DisplayName("reconnecting phase maps to UNKNOWN, matching the top bar's CONNECTING convention")
    fun reconnectingMapsToUnknown() {
        val status = VoiceChannelStatusMapper.map(
            baseState(connectionPhase = VoiceRuntimeState.ConnectionPhase.RECONNECTING)
        )

        assertEquals(StatusLevel.UNKNOWN, status.connection)
    }

    @Test
    @DisplayName("headline reports connected and ready when everything is UP")
    fun headlineReflectsHealthyState() {
        val status = VoiceChannelStatusMapper.map(baseState())

        assertEquals("Voice channel connected and ready", status.headline)
    }

    @Test
    @DisplayName("unknown() placeholder never claims a healthy or degraded connection")
    fun unknownPlaceholderIsNeutral() {
        val status = VoiceChannelStatusMapper.unknown()

        assertEquals(StatusLevel.UNKNOWN, status.connection)
        assertNotEquals(StatusLevel.UP, status.connection)
        assertNotEquals(StatusLevel.DEGRADED, status.connection)
    }
}
