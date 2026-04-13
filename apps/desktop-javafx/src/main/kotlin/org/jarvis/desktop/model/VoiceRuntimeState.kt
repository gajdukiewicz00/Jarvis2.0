package org.jarvis.desktop.model

import java.time.Instant

/**
 * Structured snapshot of the entire voice subsystem runtime.
 *
 * Consumed by HomeTab, DesktopRuntimeMonitor, and any future surface
 * that needs to display or react to voice state.
 */
data class VoiceRuntimeState(
    val sessionState: VoiceState,
    val connectionPhase: ConnectionPhase,
    val pushToTalkActive: Boolean,
    val alwaysListeningActive: Boolean,
    val currentCorrelationId: String?,
    val inputDevice: AudioDeviceInfo?,
    val outputDevice: AudioDeviceInfo?,
    val availableInputDevices: List<AudioDeviceInfo> = emptyList(),
    val availableOutputDevices: List<AudioDeviceInfo> = emptyList(),
    val lastError: String?,
    val updatedAt: Instant,
    /** Server-reported STT availability (false when no model loaded / noop provider). */
    val sttAvailable: Boolean = true,
    /** Server-reported TTS availability (false when replies degrade to text-only). */
    val ttsAvailable: Boolean = true
) {

    enum class ConnectionPhase {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        FAILED;

        fun isUsable(): Boolean = this == CONNECTED
    }

    data class AudioDeviceInfo(
        val name: String,
        val available: Boolean
    )

    val isRecording: Boolean
        get() = sessionState == VoiceState.LISTENING

    val canStartSession: Boolean
        get() = sessionState in STARTABLE_STATES && connectionPhase.isUsable() && sttAvailable

    val canCancel: Boolean
        get() = sessionState in CANCELLABLE_STATES

    val isBusy: Boolean
        get() = sessionState in BUSY_STATES

    val hasUsableInput: Boolean
        get() = inputDevice != null && inputDevice.available

    val hasUsableOutput: Boolean
        get() = outputDevice != null && outputDevice.available

    companion object {
        private val STARTABLE_STATES = setOf(VoiceState.IDLE, VoiceState.LISTENING_WAKE_WORD)
        private val CANCELLABLE_STATES = setOf(VoiceState.LISTENING, VoiceState.PROCESSING, VoiceState.TTS_PLAYBACK)
        private val BUSY_STATES = setOf(VoiceState.LISTENING, VoiceState.PROCESSING, VoiceState.TTS_PLAYBACK, VoiceState.COOLDOWN)

        fun disconnected(now: Instant): VoiceRuntimeState = VoiceRuntimeState(
            sessionState = VoiceState.IDLE,
            connectionPhase = ConnectionPhase.DISCONNECTED,
            pushToTalkActive = false,
            alwaysListeningActive = false,
            currentCorrelationId = null,
            inputDevice = null,
            outputDevice = null,
            availableInputDevices = emptyList(),
            availableOutputDevices = emptyList(),
            lastError = null,
            updatedAt = now,
            sttAvailable = true,
            ttsAvailable = true
        )
    }
}
