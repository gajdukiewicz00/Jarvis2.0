package org.jarvis.desktop.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class VoiceRuntimeStateTest {

    private val now = Instant.parse("2026-03-14T10:00:00Z")

    private fun state(
        session: VoiceState = VoiceState.IDLE,
        connection: VoiceRuntimeState.ConnectionPhase = VoiceRuntimeState.ConnectionPhase.CONNECTED,
        ptt: Boolean = false,
        alwaysListening: Boolean = false,
        error: String? = null
    ) = VoiceRuntimeState(
        sessionState = session,
        connectionPhase = connection,
        pushToTalkActive = ptt,
        alwaysListeningActive = alwaysListening,
        currentCorrelationId = null,
        inputDevice = null,
        outputDevice = null,
        lastError = error,
        updatedAt = now
    )

    @Test
    fun `canStartSession is true for IDLE + CONNECTED`() {
        assertTrue(state(VoiceState.IDLE, VoiceRuntimeState.ConnectionPhase.CONNECTED).canStartSession)
    }

    @Test
    fun `canStartSession is true for LISTENING_WAKE_WORD + CONNECTED`() {
        assertTrue(state(VoiceState.LISTENING_WAKE_WORD, VoiceRuntimeState.ConnectionPhase.CONNECTED).canStartSession)
    }

    @Test
    fun `canStartSession is false when disconnected`() {
        assertFalse(state(VoiceState.IDLE, VoiceRuntimeState.ConnectionPhase.DISCONNECTED).canStartSession)
    }

    @Test
    fun `canStartSession is false when already listening`() {
        assertFalse(state(VoiceState.LISTENING).canStartSession)
    }

    @Test
    fun `canCancel is true for LISTENING, PROCESSING, TTS_PLAYBACK`() {
        assertTrue(state(VoiceState.LISTENING).canCancel)
        assertTrue(state(VoiceState.PROCESSING).canCancel)
        assertTrue(state(VoiceState.TTS_PLAYBACK).canCancel)
    }

    @Test
    fun `canCancel is false for IDLE, COOLDOWN, ERROR`() {
        assertFalse(state(VoiceState.IDLE).canCancel)
        assertFalse(state(VoiceState.COOLDOWN).canCancel)
        assertFalse(state(VoiceState.ERROR).canCancel)
    }

    @Test
    fun `isRecording only for LISTENING state`() {
        assertTrue(state(VoiceState.LISTENING).isRecording)
        assertFalse(state(VoiceState.PROCESSING).isRecording)
        assertFalse(state(VoiceState.IDLE).isRecording)
    }

    @Test
    fun `isBusy covers active session states`() {
        assertTrue(state(VoiceState.LISTENING).isBusy)
        assertTrue(state(VoiceState.PROCESSING).isBusy)
        assertTrue(state(VoiceState.TTS_PLAYBACK).isBusy)
        assertTrue(state(VoiceState.COOLDOWN).isBusy)
        assertFalse(state(VoiceState.IDLE).isBusy)
        assertFalse(state(VoiceState.LISTENING_WAKE_WORD).isBusy)
    }

    @Test
    fun `disconnected factory creates expected defaults`() {
        val s = VoiceRuntimeState.disconnected(now)
        assertEquals(VoiceState.IDLE, s.sessionState)
        assertEquals(VoiceRuntimeState.ConnectionPhase.DISCONNECTED, s.connectionPhase)
        assertFalse(s.pushToTalkActive)
        assertFalse(s.alwaysListeningActive)
        assertNull(s.currentCorrelationId)
        assertNull(s.inputDevice)
        assertNull(s.lastError)
    }

    @Test
    fun `ConnectionPhase isUsable only for CONNECTED`() {
        assertTrue(VoiceRuntimeState.ConnectionPhase.CONNECTED.isUsable())
        assertFalse(VoiceRuntimeState.ConnectionPhase.DISCONNECTED.isUsable())
        assertFalse(VoiceRuntimeState.ConnectionPhase.RECONNECTING.isUsable())
        assertFalse(VoiceRuntimeState.ConnectionPhase.FAILED.isUsable())
    }

    @Test
    fun `hasUsableInput is true only when device exists and is available`() {
        val withDevice = state().copy(
            inputDevice = VoiceRuntimeState.AudioDeviceInfo("Mic", true)
        )
        assertTrue(withDevice.hasUsableInput)

        val unavailable = state().copy(
            inputDevice = VoiceRuntimeState.AudioDeviceInfo("Mic", false)
        )
        assertFalse(unavailable.hasUsableInput)

        assertFalse(state().hasUsableInput)
    }

    @Test
    fun `hasUsableOutput is true only when device exists and is available`() {
        val withDevice = state().copy(
            outputDevice = VoiceRuntimeState.AudioDeviceInfo("Speaker", true)
        )
        assertTrue(withDevice.hasUsableOutput)

        assertFalse(state().hasUsableOutput)
    }

    @Test
    fun `device lists default to empty`() {
        val s = state()
        assertTrue(s.availableInputDevices.isEmpty())
        assertTrue(s.availableOutputDevices.isEmpty())
    }

    @Test
    fun `disconnected factory has empty device lists`() {
        val s = VoiceRuntimeState.disconnected(now)
        assertTrue(s.availableInputDevices.isEmpty())
        assertTrue(s.availableOutputDevices.isEmpty())
    }
}
