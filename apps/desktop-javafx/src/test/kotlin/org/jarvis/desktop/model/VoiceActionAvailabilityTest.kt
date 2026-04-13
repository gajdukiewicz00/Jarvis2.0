package org.jarvis.desktop.model

import org.jarvis.desktop.model.VoiceRuntimeState.AudioDeviceInfo
import org.jarvis.desktop.model.VoiceRuntimeState.ConnectionPhase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class VoiceActionAvailabilityTest {

    private val now = Instant.parse("2026-03-14T10:00:00Z")
    private val mic = AudioDeviceInfo("Default Mic", true)
    private val mic2 = AudioDeviceInfo("USB Mic", true)
    private val speaker = AudioDeviceInfo("Default Speaker", true)
    private val speaker2 = AudioDeviceInfo("HDMI Out", true)

    private fun state(
        session: VoiceState = VoiceState.IDLE,
        connection: ConnectionPhase = ConnectionPhase.CONNECTED,
        ptt: Boolean = false,
        alwaysListening: Boolean = false,
        inputDevice: AudioDeviceInfo? = mic,
        outputDevice: AudioDeviceInfo? = speaker,
        inputDevices: List<AudioDeviceInfo> = listOf(mic),
        outputDevices: List<AudioDeviceInfo> = listOf(speaker),
        error: String? = null
    ) = VoiceRuntimeState(
        sessionState = session,
        connectionPhase = connection,
        pushToTalkActive = ptt,
        alwaysListeningActive = alwaysListening,
        currentCorrelationId = null,
        inputDevice = inputDevice,
        outputDevice = outputDevice,
        availableInputDevices = inputDevices,
        availableOutputDevices = outputDevices,
        lastError = error,
        updatedAt = now
    )

    // ── push-to-talk start ──────────────────────────────────────────

    @Nested
    @DisplayName("Push-to-talk start")
    inner class PushToTalkStart {

        @Test
        fun `allowed when connected, idle, and mic available`() {
            assertTrue(VoiceActionAvailability.from(state()).canPushToTalkStart)
        }

        @Test
        fun `allowed when listening for wake word`() {
            assertTrue(VoiceActionAvailability.from(
                state(session = VoiceState.LISTENING_WAKE_WORD)
            ).canPushToTalkStart)
        }

        @Test
        fun `blocked when disconnected`() {
            assertFalse(VoiceActionAvailability.from(
                state(connection = ConnectionPhase.DISCONNECTED)
            ).canPushToTalkStart)
        }

        @Test
        fun `blocked when connection failed`() {
            assertFalse(VoiceActionAvailability.from(
                state(connection = ConnectionPhase.FAILED)
            ).canPushToTalkStart)
        }

        @Test
        fun `blocked when already listening`() {
            assertFalse(VoiceActionAvailability.from(
                state(session = VoiceState.LISTENING)
            ).canPushToTalkStart)
        }

        @Test
        fun `blocked when processing`() {
            assertFalse(VoiceActionAvailability.from(
                state(session = VoiceState.PROCESSING)
            ).canPushToTalkStart)
        }

        @Test
        fun `blocked during TTS playback`() {
            assertFalse(VoiceActionAvailability.from(
                state(session = VoiceState.TTS_PLAYBACK)
            ).canPushToTalkStart)
        }

        @Test
        fun `blocked during cooldown`() {
            assertFalse(VoiceActionAvailability.from(
                state(session = VoiceState.COOLDOWN)
            ).canPushToTalkStart)
        }

        @Test
        fun `blocked when no input device`() {
            assertFalse(VoiceActionAvailability.from(
                state(inputDevice = null)
            ).canPushToTalkStart)
        }

        @Test
        fun `blocked when input device unavailable`() {
            assertFalse(VoiceActionAvailability.from(
                state(inputDevice = AudioDeviceInfo("Dead Mic", false))
            ).canPushToTalkStart)
        }
    }

    // ── push-to-talk release ────────────────────────────────────────

    @Nested
    @DisplayName("Push-to-talk release")
    inner class PushToTalkRelease {

        @Test
        fun `allowed when listening with PTT active`() {
            assertTrue(VoiceActionAvailability.from(
                state(session = VoiceState.LISTENING, ptt = true)
            ).canPushToTalkRelease)
        }

        @Test
        fun `blocked when listening without PTT`() {
            assertFalse(VoiceActionAvailability.from(
                state(session = VoiceState.LISTENING, ptt = false)
            ).canPushToTalkRelease)
        }

        @Test
        fun `blocked when idle with stale PTT flag`() {
            assertFalse(VoiceActionAvailability.from(
                state(session = VoiceState.IDLE, ptt = true)
            ).canPushToTalkRelease)
        }

        @Test
        fun `blocked when processing even with PTT flag`() {
            assertFalse(VoiceActionAvailability.from(
                state(session = VoiceState.PROCESSING, ptt = true)
            ).canPushToTalkRelease)
        }
    }

    // ── cancel session ──────────────────────────────────────────────

    @Nested
    @DisplayName("Cancel session")
    inner class CancelSession {

        @Test
        fun `allowed when listening`() {
            assertTrue(VoiceActionAvailability.from(
                state(session = VoiceState.LISTENING)
            ).canCancelSession)
        }

        @Test
        fun `allowed when processing`() {
            assertTrue(VoiceActionAvailability.from(
                state(session = VoiceState.PROCESSING)
            ).canCancelSession)
        }

        @Test
        fun `allowed during TTS playback`() {
            assertTrue(VoiceActionAvailability.from(
                state(session = VoiceState.TTS_PLAYBACK)
            ).canCancelSession)
        }

        @Test
        fun `blocked when idle`() {
            assertFalse(VoiceActionAvailability.from(
                state(session = VoiceState.IDLE)
            ).canCancelSession)
        }

        @Test
        fun `blocked during cooldown`() {
            assertFalse(VoiceActionAvailability.from(
                state(session = VoiceState.COOLDOWN)
            ).canCancelSession)
        }

        @Test
        fun `blocked in error state`() {
            assertFalse(VoiceActionAvailability.from(
                state(session = VoiceState.ERROR)
            ).canCancelSession)
        }
    }

    // ── refresh devices ─────────────────────────────────────────────

    @Nested
    @DisplayName("Refresh devices")
    inner class RefreshDevices {

        @Test
        fun `allowed when idle`() {
            assertTrue(VoiceActionAvailability.from(state()).canRefreshDevices)
        }

        @Test
        fun `allowed when disconnected`() {
            assertTrue(VoiceActionAvailability.from(
                state(connection = ConnectionPhase.DISCONNECTED)
            ).canRefreshDevices)
        }

        @Test
        fun `allowed during processing`() {
            assertTrue(VoiceActionAvailability.from(
                state(session = VoiceState.PROCESSING)
            ).canRefreshDevices)
        }

        @Test
        fun `allowed during TTS playback`() {
            assertTrue(VoiceActionAvailability.from(
                state(session = VoiceState.TTS_PLAYBACK)
            ).canRefreshDevices)
        }

        @Test
        fun `blocked during active recording`() {
            assertFalse(VoiceActionAvailability.from(
                state(session = VoiceState.LISTENING)
            ).canRefreshDevices)
        }
    }

    // ── select input device ─────────────────────────────────────────

    @Nested
    @DisplayName("Select input device")
    inner class SelectInputDevice {

        @Test
        fun `allowed when connected, idle, and multiple inputs exist`() {
            assertTrue(VoiceActionAvailability.from(
                state(inputDevices = listOf(mic, mic2))
            ).canSelectInputDevice)
        }

        @Test
        fun `blocked with only one input device`() {
            assertFalse(VoiceActionAvailability.from(state()).canSelectInputDevice)
        }

        @Test
        fun `blocked when disconnected`() {
            assertFalse(VoiceActionAvailability.from(
                state(connection = ConnectionPhase.DISCONNECTED, inputDevices = listOf(mic, mic2))
            ).canSelectInputDevice)
        }

        @Test
        fun `blocked when busy`() {
            assertFalse(VoiceActionAvailability.from(
                state(session = VoiceState.LISTENING, inputDevices = listOf(mic, mic2))
            ).canSelectInputDevice)
        }
    }

    // ── select output device ────────────────────────────────────────

    @Nested
    @DisplayName("Select output device")
    inner class SelectOutputDevice {

        @Test
        fun `allowed when connected, idle, and multiple outputs exist`() {
            assertTrue(VoiceActionAvailability.from(
                state(outputDevices = listOf(speaker, speaker2))
            ).canSelectOutputDevice)
        }

        @Test
        fun `blocked with only one output device`() {
            assertFalse(VoiceActionAvailability.from(state()).canSelectOutputDevice)
        }

        @Test
        fun `blocked when busy`() {
            assertFalse(VoiceActionAvailability.from(
                state(session = VoiceState.PROCESSING, outputDevices = listOf(speaker, speaker2))
            ).canSelectOutputDevice)
        }
    }

    // ── toggle always listening ─────────────────────────────────────

    @Nested
    @DisplayName("Toggle always listening")
    inner class ToggleAlwaysListening {

        @Test
        fun `enable allowed when connected`() {
            assertTrue(VoiceActionAvailability.from(state()).canToggleAlwaysListening)
        }

        @Test
        fun `disable always allowed — even when disconnected`() {
            assertTrue(VoiceActionAvailability.from(
                state(connection = ConnectionPhase.DISCONNECTED, alwaysListening = true)
            ).canToggleAlwaysListening)
        }

        @Test
        fun `enable blocked when disconnected`() {
            assertFalse(VoiceActionAvailability.from(
                state(connection = ConnectionPhase.DISCONNECTED, alwaysListening = false)
            ).canToggleAlwaysListening)
        }

        @Test
        fun `enable blocked when connection failed`() {
            assertFalse(VoiceActionAvailability.from(
                state(connection = ConnectionPhase.FAILED, alwaysListening = false)
            ).canToggleAlwaysListening)
        }
    }

    // ── cross-cutting scenarios ─────────────────────────────────────

    @Nested
    @DisplayName("Cross-cutting state scenarios")
    inner class CrossCutting {

        @Test
        fun `disconnected state disables most actions but allows refresh`() {
            val actions = VoiceActionAvailability.from(
                state(connection = ConnectionPhase.DISCONNECTED)
            )
            assertFalse(actions.canPushToTalkStart)
            assertFalse(actions.canPushToTalkRelease)
            assertFalse(actions.canCancelSession)
            assertFalse(actions.canSelectInputDevice)
            assertFalse(actions.canSelectOutputDevice)
            assertFalse(actions.canToggleAlwaysListening)
            assertTrue(actions.canRefreshDevices)
        }

        @Test
        fun `connected idle is the most permissive base state`() {
            val actions = VoiceActionAvailability.from(state())
            assertTrue(actions.canPushToTalkStart)
            assertFalse(actions.canPushToTalkRelease)
            assertFalse(actions.canCancelSession)
            assertTrue(actions.canRefreshDevices)
            assertTrue(actions.canToggleAlwaysListening)
        }

        @Test
        fun `active PTT recording — release and cancel available, start blocked`() {
            val actions = VoiceActionAvailability.from(
                state(session = VoiceState.LISTENING, ptt = true)
            )
            assertFalse(actions.canPushToTalkStart)
            assertTrue(actions.canPushToTalkRelease)
            assertTrue(actions.canCancelSession)
            assertFalse(actions.canRefreshDevices)
            assertFalse(actions.canSelectInputDevice)
            assertFalse(actions.canSelectOutputDevice)
        }

        @Test
        fun `error state is restrictive`() {
            val actions = VoiceActionAvailability.from(
                state(session = VoiceState.ERROR)
            )
            assertFalse(actions.canPushToTalkStart)
            assertFalse(actions.canPushToTalkRelease)
            assertFalse(actions.canCancelSession)
            assertTrue(actions.canRefreshDevices)
        }

        @Test
        fun `cooldown blocks session-related actions`() {
            val actions = VoiceActionAvailability.from(
                state(session = VoiceState.COOLDOWN)
            )
            assertFalse(actions.canPushToTalkStart)
            assertFalse(actions.canPushToTalkRelease)
            assertFalse(actions.canCancelSession)
            assertTrue(actions.canRefreshDevices)
        }
    }
}
