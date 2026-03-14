package org.jarvis.desktop.model

import org.jarvis.desktop.model.VoiceEventClassifier.Severity
import org.jarvis.desktop.model.VoiceRuntimeState.AudioDeviceInfo
import org.jarvis.desktop.model.VoiceRuntimeState.ConnectionPhase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class VoiceEventClassifierTest {

    private val now = Instant.parse("2026-03-14T10:00:00Z")
    private val mic = AudioDeviceInfo("Default Mic", true)
    private val speaker = AudioDeviceInfo("Default Speaker", true)

    private fun state(
        session: VoiceState = VoiceState.IDLE,
        connection: ConnectionPhase = ConnectionPhase.CONNECTED,
        ptt: Boolean = false,
        alwaysListening: Boolean = false,
        inputDevice: AudioDeviceInfo? = mic,
        outputDevice: AudioDeviceInfo? = speaker,
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

    // ── session lifecycle ────────────────────────────────────────────

    @Nested
    @DisplayName("Session transitions")
    inner class SessionTransitions {

        @Test
        fun `IDLE to LISTENING with PTT — push-to-talk started`() {
            val events = VoiceEventClassifier.classify(
                state(session = VoiceState.IDLE),
                state(session = VoiceState.LISTENING, ptt = true)
            )
            assertEquals(1, events.size)
            assertEquals("Push-to-talk started", events[0].title)
            assertEquals(Severity.INFO, events[0].severity)
        }

        @Test
        fun `IDLE to LISTENING without PTT — listening to command`() {
            val events = VoiceEventClassifier.classify(
                state(session = VoiceState.IDLE),
                state(session = VoiceState.LISTENING, ptt = false)
            )
            assertEquals(1, events.size)
            assertEquals("Listening to command", events[0].title)
        }

        @Test
        fun `LISTENING_WAKE_WORD to LISTENING — listening to command`() {
            val events = VoiceEventClassifier.classify(
                state(session = VoiceState.LISTENING_WAKE_WORD),
                state(session = VoiceState.LISTENING, ptt = false)
            )
            assertEquals(1, events.size)
            assertEquals("Listening to command", events[0].title)
        }

        @Test
        fun `LISTENING to PROCESSING — processing voice command`() {
            val events = VoiceEventClassifier.classify(
                state(session = VoiceState.LISTENING),
                state(session = VoiceState.PROCESSING)
            )
            assertEquals(1, events.size)
            assertEquals("Processing voice command", events[0].title)
            assertEquals(Severity.INFO, events[0].severity)
        }

        @Test
        fun `PROCESSING to TTS_PLAYBACK — Jarvis responding`() {
            val events = VoiceEventClassifier.classify(
                state(session = VoiceState.PROCESSING),
                state(session = VoiceState.TTS_PLAYBACK)
            )
            assertEquals(1, events.size)
            assertEquals("Jarvis responding", events[0].title)
            assertEquals(Severity.SUCCESS, events[0].severity)
        }

        @Test
        fun `COOLDOWN to IDLE — voice session completed`() {
            val events = VoiceEventClassifier.classify(
                state(session = VoiceState.COOLDOWN),
                state(session = VoiceState.IDLE)
            )
            assertEquals(1, events.size)
            assertEquals("Voice session completed", events[0].title)
            assertEquals(Severity.SUCCESS, events[0].severity)
        }

        @Test
        fun `LISTENING to IDLE — voice session cancelled`() {
            val events = VoiceEventClassifier.classify(
                state(session = VoiceState.LISTENING),
                state(session = VoiceState.IDLE)
            )
            assertEquals(1, events.size)
            assertEquals("Voice session cancelled", events[0].title)
            assertEquals(Severity.INFO, events[0].severity)
        }

        @Test
        fun `PROCESSING to IDLE — voice session cancelled`() {
            val events = VoiceEventClassifier.classify(
                state(session = VoiceState.PROCESSING),
                state(session = VoiceState.IDLE)
            )
            assertEquals(1, events.size)
            assertEquals("Voice session cancelled", events[0].title)
        }

        @Test
        fun `TTS_PLAYBACK to IDLE — voice session cancelled`() {
            val events = VoiceEventClassifier.classify(
                state(session = VoiceState.TTS_PLAYBACK),
                state(session = VoiceState.IDLE)
            )
            assertEquals(1, events.size)
            assertEquals("Voice session cancelled", events[0].title)
        }

        @Test
        fun `ERROR to IDLE — voice session recovered`() {
            val events = VoiceEventClassifier.classify(
                state(session = VoiceState.ERROR),
                state(session = VoiceState.IDLE)
            )
            assertEquals(1, events.size)
            assertEquals("Voice session recovered", events[0].title)
            assertEquals(Severity.INFO, events[0].severity)
        }

        @Test
        fun `TTS_PLAYBACK to COOLDOWN — no event (transient)`() {
            val events = VoiceEventClassifier.classify(
                state(session = VoiceState.TTS_PLAYBACK),
                state(session = VoiceState.COOLDOWN)
            )
            assertTrue(events.isEmpty())
        }

        @Test
        fun `IDLE to LISTENING_WAKE_WORD — no event (quiet transition)`() {
            val events = VoiceEventClassifier.classify(
                state(session = VoiceState.IDLE),
                state(session = VoiceState.LISTENING_WAKE_WORD)
            )
            assertTrue(events.isEmpty())
        }

        @Test
        fun `transition to ERROR — no session event (handled by error classifier)`() {
            val events = VoiceEventClassifier.classifySessionTransition(
                state(session = VoiceState.LISTENING),
                state(session = VoiceState.ERROR, error = "mic broke")
            )
            assertNull(events)
        }
    }

    // ── push-to-talk release ────────────────────────────────────────

    @Nested
    @DisplayName("Push-to-talk release")
    inner class PttRelease {

        @Test
        fun `PTT deactivated while still LISTENING — release event`() {
            val events = VoiceEventClassifier.classify(
                state(session = VoiceState.LISTENING, ptt = true),
                state(session = VoiceState.LISTENING, ptt = false)
            )
            assertEquals(1, events.size)
            assertEquals("Push-to-talk released", events[0].title)
        }

        @Test
        fun `PTT deactivated with simultaneous state change to PROCESSING — only processing event`() {
            val events = VoiceEventClassifier.classify(
                state(session = VoiceState.LISTENING, ptt = true),
                state(session = VoiceState.PROCESSING, ptt = false)
            )
            assertEquals(1, events.size)
            assertEquals("Processing voice command", events[0].title)
        }

        @Test
        fun `PTT deactivated while IDLE — no release event`() {
            val events = VoiceEventClassifier.classifyPttRelease(
                state(session = VoiceState.IDLE, ptt = true),
                state(session = VoiceState.IDLE, ptt = false)
            )
            assertNull(events)
        }
    }

    // ── error classification ────────────────────────────────────────

    @Nested
    @DisplayName("Error classification")
    inner class ErrorEvents {

        @Test
        fun `new error produces classified event`() {
            val events = VoiceEventClassifier.classify(
                state(),
                state(error = "Microphone failed")
            )
            assertTrue(events.any { it.severity == Severity.ERROR })
            assertTrue(events.any { it.title.contains("unavailable", ignoreCase = true) })
        }

        @Test
        fun `same error across two snapshots — no event`() {
            val events = VoiceEventClassifier.classify(
                state(error = "some error"),
                state(error = "some error")
            )
            assertTrue(events.isEmpty())
        }

        @Test
        fun `error cleared — no event`() {
            val events = VoiceEventClassifier.classify(
                state(error = "old error"),
                state(error = null)
            )
            assertTrue(events.isEmpty())
        }

        @Test
        fun `timeout error produces WARNING`() {
            val events = VoiceEventClassifier.classify(
                state(),
                state(error = "STT recognition timed out")
            )
            val timeoutEvent = events.first { it.title.contains("timed out", ignoreCase = true) }
            assertEquals(Severity.WARNING, timeoutEvent.severity)
        }

        @Test
        fun `error changed from one to another produces event for new error`() {
            val events = VoiceEventClassifier.classify(
                state(error = "first error"),
                state(error = "Permission denied for microphone")
            )
            assertTrue(events.any { it.title.contains("permission", ignoreCase = true) })
        }

        @Test
        fun `classified error includes guidance as details`() {
            val events = VoiceEventClassifier.classify(
                state(),
                state(error = "Audio device busy, line unavailable")
            )
            val event = events.first { it.severity == Severity.ERROR }
            assertTrue(event.details.isNotBlank())
        }
    }

    // ── device loss ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Device loss")
    inner class DeviceLoss {

        @Test
        fun `input device becomes unavailable while connected`() {
            val events = VoiceEventClassifier.classify(
                state(inputDevice = mic),
                state(inputDevice = AudioDeviceInfo("Default Mic", false))
            )
            assertTrue(events.any { it.title == "Microphone became unavailable" })
            assertTrue(events.any { it.severity == Severity.WARNING })
        }

        @Test
        fun `input device removed while connected`() {
            val events = VoiceEventClassifier.classify(
                state(inputDevice = mic),
                state(inputDevice = null)
            )
            assertTrue(events.any { it.title == "Microphone became unavailable" })
            assertTrue(events.any { it.details.contains("No input device") })
        }

        @Test
        fun `device loss ignored when not connected`() {
            val events = VoiceEventClassifier.classify(
                state(inputDevice = mic, connection = ConnectionPhase.DISCONNECTED),
                state(inputDevice = null, connection = ConnectionPhase.DISCONNECTED)
            )
            assertTrue(events.none { it.title.contains("Microphone") })
        }

        @Test
        fun `device gained — no event`() {
            val events = VoiceEventClassifier.classify(
                state(inputDevice = null),
                state(inputDevice = mic)
            )
            assertTrue(events.none { it.title.contains("Microphone") })
        }
    }

    // ── edge cases ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    inner class EdgeCases {

        @Test
        fun `null previous produces empty list`() {
            assertTrue(VoiceEventClassifier.classify(null, state()).isEmpty())
        }

        @Test
        fun `identical snapshots produce empty list`() {
            val s = state()
            assertTrue(VoiceEventClassifier.classify(s, s).isEmpty())
        }

        @Test
        fun `multiple events in one transition (session + error)`() {
            val prev = state(session = VoiceState.LISTENING)
            val curr = state(session = VoiceState.IDLE, error = "WebSocket closed")
            val events = VoiceEventClassifier.classify(prev, curr)
            assertTrue(events.size >= 2)
            assertTrue(events.any { it.title == "Voice session cancelled" })
            assertTrue(events.any { it.severity == Severity.ERROR })
        }
    }
}
