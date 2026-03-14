package org.jarvis.desktop.model

import org.jarvis.desktop.model.VoiceRuntimeState.AudioDeviceInfo
import org.jarvis.desktop.model.VoiceRuntimeState.ConnectionPhase
import org.jarvis.desktop.model.VoiceUxStatus.Severity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class VoiceUxStatusTest {

    private val now = Instant.parse("2026-03-14T10:00:00Z")

    private fun state(
        session: VoiceState = VoiceState.IDLE,
        connection: ConnectionPhase = ConnectionPhase.CONNECTED,
        ptt: Boolean = false,
        alwaysListening: Boolean = false,
        inputDevice: AudioDeviceInfo? = AudioDeviceInfo("Default Mic", true),
        outputDevice: AudioDeviceInfo? = AudioDeviceInfo("Default Speaker", true),
        error: String? = null
    ) = VoiceRuntimeState(
        sessionState = session,
        connectionPhase = connection,
        pushToTalkActive = ptt,
        alwaysListeningActive = alwaysListening,
        currentCorrelationId = null,
        inputDevice = inputDevice,
        outputDevice = outputDevice,
        lastError = error,
        updatedAt = now
    )

    // ── error classification takes priority ─────────────────────────

    @Nested
    @DisplayName("Error classification")
    inner class ErrorClassification {

        @Test
        fun `permission denied error produces ERROR with guidance`() {
            val s = state(error = "Permission denied for audio capture")
            val result = VoiceUxStatus.compute(s)
            assertEquals(Severity.ERROR, result.severity)
            assertTrue(result.headline.contains("permission", ignoreCase = true))
            assertNotNull(result.guidance)
        }

        @Test
        fun `access denied variant`() {
            val result = VoiceUxStatus.classifyRawError("Access denied by system")
            assertEquals(Severity.ERROR, result.severity)
            assertTrue(result.headline.contains("permission", ignoreCase = true))
        }

        @Test
        fun `device busy error`() {
            val result = VoiceUxStatus.classifyRawError("Audio device busy — cannot open line")
            assertEquals(Severity.ERROR, result.severity)
            assertTrue(result.headline.contains("busy", ignoreCase = true))
        }

        @Test
        fun `line unavailable variant`() {
            val result = VoiceUxStatus.classifyRawError("Line unavailable")
            assertEquals(Severity.ERROR, result.severity)
            assertTrue(result.headline.contains("busy", ignoreCase = true))
        }

        @Test
        fun `microphone unavailable error`() {
            val result = VoiceUxStatus.classifyRawError("No line matching for capture device")
            assertEquals(Severity.ERROR, result.severity)
            assertTrue(result.headline.contains("unavailable", ignoreCase = true))
        }

        @Test
        fun `timeout error`() {
            val result = VoiceUxStatus.classifyRawError("STT recognition timed out")
            assertEquals(Severity.WARNING, result.severity)
            assertTrue(result.headline.contains("timed out", ignoreCase = true))
        }

        @Test
        fun `audio recording error`() {
            val result = VoiceUxStatus.classifyRawError("Audio recording error: TargetDataLine failure")
            assertEquals(Severity.ERROR, result.severity)
            assertTrue(result.headline.contains("recording", ignoreCase = true))
        }

        @Test
        fun `websocket connection error`() {
            val result = VoiceUxStatus.classifyRawError("WebSocket closed unexpectedly")
            assertEquals(Severity.ERROR, result.severity)
            assertTrue(result.headline.contains("connection", ignoreCase = true))
        }

        @Test
        fun `unknown error falls back to WARNING`() {
            val result = VoiceUxStatus.classifyRawError("something weird happened")
            assertEquals(Severity.WARNING, result.severity)
            assertTrue(result.headline.contains("something weird happened"))
        }

        @Test
        fun `error takes priority over connection and session state`() {
            val s = state(
                session = VoiceState.LISTENING,
                connection = ConnectionPhase.CONNECTED,
                error = "Microphone failed"
            )
            val result = VoiceUxStatus.compute(s)
            assertEquals(Severity.ERROR, result.severity)
            assertTrue(result.headline.contains("unavailable", ignoreCase = true))
        }
    }

    // ── connection problems ─────────────────────────────────────────

    @Nested
    @DisplayName("Connection problems")
    inner class ConnectionProblems {

        @Test
        fun `DISCONNECTED produces WARNING`() {
            val s = state(connection = ConnectionPhase.DISCONNECTED)
            val result = VoiceUxStatus.compute(s)
            assertEquals(Severity.WARNING, result.severity)
            assertTrue(result.headline.contains("disconnected", ignoreCase = true))
        }

        @Test
        fun `FAILED produces ERROR`() {
            val s = state(connection = ConnectionPhase.FAILED)
            val result = VoiceUxStatus.compute(s)
            assertEquals(Severity.ERROR, result.severity)
            assertTrue(result.headline.contains("failed", ignoreCase = true))
        }

        @Test
        fun `RECONNECTING produces WARNING`() {
            val s = state(connection = ConnectionPhase.RECONNECTING)
            val result = VoiceUxStatus.compute(s)
            assertEquals(Severity.WARNING, result.severity)
            assertTrue(result.headline.contains("reconnecting", ignoreCase = true))
        }

        @Test
        fun `CONNECTING produces INFO`() {
            val s = state(connection = ConnectionPhase.CONNECTING)
            val result = VoiceUxStatus.compute(s)
            assertEquals(Severity.INFO, result.severity)
        }

        @Test
        fun `connection problem takes priority over session state`() {
            val s = state(session = VoiceState.LISTENING, connection = ConnectionPhase.FAILED)
            val result = VoiceUxStatus.compute(s)
            assertEquals(Severity.ERROR, result.severity)
        }
    }

    // ── device problems ─────────────────────────────────────────────

    @Nested
    @DisplayName("Device problems")
    inner class DeviceProblems {

        @Test
        fun `no input device produces WARNING when connected`() {
            val s = state(inputDevice = null)
            val result = VoiceUxStatus.compute(s)
            assertEquals(Severity.WARNING, result.severity)
            assertTrue(result.headline.contains("microphone", ignoreCase = true))
            assertNotNull(result.guidance)
        }

        @Test
        fun `unavailable input device produces WARNING`() {
            val s = state(inputDevice = AudioDeviceInfo("Broken Mic", available = false))
            val result = VoiceUxStatus.compute(s)
            assertEquals(Severity.WARNING, result.severity)
            assertTrue(result.headline.contains("not available", ignoreCase = true))
        }

        @Test
        fun `device problem only checked when connected`() {
            val s = state(inputDevice = null, connection = ConnectionPhase.DISCONNECTED)
            val result = VoiceUxStatus.compute(s)
            assertTrue(result.headline.contains("disconnected", ignoreCase = true))
        }
    }

    // ── session status ──────────────────────────────────────────────

    @Nested
    @DisplayName("Session status (happy path)")
    inner class SessionStatus {

        @Test
        fun `IDLE without always-listening shows guidance`() {
            val s = state(session = VoiceState.IDLE, alwaysListening = false)
            val result = VoiceUxStatus.compute(s)
            assertEquals(Severity.INFO, result.severity)
            assertNotNull(result.guidance)
        }

        @Test
        fun `IDLE with always-listening has no guidance`() {
            val s = state(session = VoiceState.IDLE, alwaysListening = true)
            val result = VoiceUxStatus.compute(s)
            assertNull(result.guidance)
        }

        @Test
        fun `LISTENING_WAKE_WORD is INFO`() {
            val result = VoiceUxStatus.compute(state(session = VoiceState.LISTENING_WAKE_WORD))
            assertEquals(Severity.INFO, result.severity)
        }

        @Test
        fun `LISTENING with PTT shows push-to-talk label`() {
            val result = VoiceUxStatus.compute(state(session = VoiceState.LISTENING, ptt = true))
            assertEquals(Severity.ACTIVE, result.severity)
            assertTrue(result.headline.contains("push-to-talk", ignoreCase = true))
        }

        @Test
        fun `LISTENING without PTT shows generic listening`() {
            val result = VoiceUxStatus.compute(state(session = VoiceState.LISTENING, ptt = false))
            assertEquals(Severity.ACTIVE, result.severity)
            assertTrue(result.headline.contains("listening", ignoreCase = true))
        }

        @Test
        fun `PROCESSING is ACTIVE`() {
            val result = VoiceUxStatus.compute(state(session = VoiceState.PROCESSING))
            assertEquals(Severity.ACTIVE, result.severity)
        }

        @Test
        fun `TTS_PLAYBACK is SUCCESS`() {
            val result = VoiceUxStatus.compute(state(session = VoiceState.TTS_PLAYBACK))
            assertEquals(Severity.SUCCESS, result.severity)
        }

        @Test
        fun `COOLDOWN is INFO`() {
            val result = VoiceUxStatus.compute(state(session = VoiceState.COOLDOWN))
            assertEquals(Severity.INFO, result.severity)
        }

        @Test
        fun `ERROR state is ERROR`() {
            val result = VoiceUxStatus.compute(state(session = VoiceState.ERROR))
            assertEquals(Severity.ERROR, result.severity)
        }
    }
}
