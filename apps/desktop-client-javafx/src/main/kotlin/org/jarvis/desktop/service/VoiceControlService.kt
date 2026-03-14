package org.jarvis.desktop.service

import org.jarvis.desktop.model.VoiceRuntimeState
import org.jarvis.desktop.model.VoiceRuntimeState.AudioDeviceInfo
import org.jarvis.desktop.model.VoiceRuntimeState.ConnectionPhase
import org.jarvis.desktop.model.VoiceState
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.withLock

/**
 * Desktop-side voice control surface.
 *
 * Wraps [VoiceSession] and [VoiceWebSocketClient] behind a single API
 * so that VoiceTab, HomeTab, and any future consumer can control voice
 * and observe [VoiceRuntimeState] without coupling to internals.
 *
 * Thread-safe: all state mutations go through [mutate].
 */
class VoiceControlService(
    private val voiceSession: VoiceSession,
    private val webSocketClient: VoiceWebSocketClient,
    private val clock: Clock = Clock.systemUTC(),
    private val audioDeviceDetector: () -> AudioDevicePair = Companion::detectAudioDevices
) {
    private val logger = LoggerFactory.getLogger(VoiceControlService::class.java)
    private val lock = ReentrantLock()
    private val listeners = CopyOnWriteArrayList<(VoiceRuntimeState) -> Unit>()

    @Volatile
    private var state = VoiceRuntimeState.disconnected(clock.instant())

    fun currentState(): VoiceRuntimeState = lock.withLock { state }

    fun addListener(listener: (VoiceRuntimeState) -> Unit) {
        listeners.add(listener)
        listener(currentState())
    }

    fun removeListener(listener: (VoiceRuntimeState) -> Unit) {
        listeners.remove(listener)
    }

    // ── control actions ─────────────────────────────────────────────

    /**
     * Begin a push-to-talk session. Returns the correlationId if the session
     * started, or null if the current state does not allow it.
     */
    fun pushToTalkStart(): String? {
        if (!state.canStartSession) {
            logger.warn("Cannot start push-to-talk: session={}, connection={}",
                state.sessionState, state.connectionPhase)
            return null
        }
        val correlationId = voiceSession.startSession(isManualTalk = true)
        if (correlationId != null) {
            webSocketClient.startCommand(correlationId)
            mutate { it.copy(pushToTalkActive = true, currentCorrelationId = correlationId) }
        }
        return correlationId
    }

    /**
     * Release the push-to-talk button. VoiceTab is still responsible for
     * calling [VoiceWebSocketClient.endOfSpeech] because it owns the
     * audio recorder lifecycle.
     */
    fun pushToTalkRelease() {
        mutate { current ->
            if (current.sessionState == VoiceState.LISTENING && current.pushToTalkActive)
                current.copy(pushToTalkActive = false)
            else current
        }
    }

    /**
     * Cancel any active voice session (recording, processing, or playback).
     */
    fun cancelCurrentSession(reason: String = "Cancelled by user") {
        if (!state.canCancel) {
            logger.debug("Nothing to cancel in state {}", state.sessionState)
            return
        }
        voiceSession.cancelSession(reason)
        mutate { current ->
            if (current.isBusy || current.pushToTalkActive)
                current.copy(pushToTalkActive = false, lastError = null)
            else current
        }
    }

    /**
     * Re-detect available audio input/output devices and update state.
     * Preserves current selection if the device is still present; otherwise
     * falls back to the first available device.
     */
    fun refreshDevices() {
        val devices = try {
            audioDeviceDetector()
        } catch (e: Exception) {
            logger.warn("Audio device detection failed: {}", e.message)
            AudioDevicePair(emptyList(), emptyList())
        }
        mutate {
            it.copy(
                inputDevice = selectFromList(devices.inputs, it.inputDevice?.name),
                outputDevice = selectFromList(devices.outputs, it.outputDevice?.name),
                availableInputDevices = devices.inputs,
                availableOutputDevices = devices.outputs
            )
        }
    }

    /**
     * Choose a specific input device by name from the available list.
     */
    fun selectInputDevice(name: String) {
        val device = state.availableInputDevices.firstOrNull { it.name == name }
        if (device != null) {
            mutate { it.copy(inputDevice = device) }
        } else {
            logger.warn("Input device '{}' not in available list", name)
        }
    }

    /**
     * Choose a specific output device by name from the available list.
     */
    fun selectOutputDevice(name: String) {
        val device = state.availableOutputDevices.firstOrNull { it.name == name }
        if (device != null) {
            mutate { it.copy(outputDevice = device) }
        } else {
            logger.warn("Output device '{}' not in available list", name)
        }
    }

    private fun selectFromList(
        available: List<AudioDeviceInfo>,
        preferredName: String?
    ): AudioDeviceInfo? {
        if (available.isEmpty()) return null
        return available.firstOrNull { it.name == preferredName } ?: available.first()
    }

    // ── state ingestion (called from VoiceTab / wiring callbacks) ───

    fun onSessionStateChanged(sessionState: VoiceState, correlationId: String?) {
        mutate {
            it.copy(
                sessionState = sessionState,
                currentCorrelationId = correlationId,
                lastError = if (sessionState == VoiceState.ERROR) it.lastError else null
            )
        }
    }

    fun onConnectionStateChanged(rawState: String) {
        val phase = mapConnectionPhase(rawState)
        val error = when {
            rawState.startsWith("ERROR", ignoreCase = true) ->
                rawState.removePrefix("ERROR:").removePrefix("ERROR").trim().ifEmpty { null }
            rawState.startsWith("UNAVAILABLE", ignoreCase = true) ->
                rawState.removePrefix("UNAVAILABLE:").removePrefix("UNAVAILABLE").trim().ifEmpty { "Voice backend not reachable" }
            else -> null
        }
        mutate { current ->
            val lostConnection = current.connectionPhase.isUsable() && !phase.isUsable()
            if (lostConnection && current.isBusy) {
                current.copy(
                    connectionPhase = phase,
                    lastError = error ?: current.lastError,
                    sessionState = VoiceState.IDLE,
                    pushToTalkActive = false,
                    currentCorrelationId = null
                )
            } else {
                current.copy(connectionPhase = phase, lastError = error ?: current.lastError)
            }
        }
    }

    fun onAlwaysListeningChanged(active: Boolean) {
        mutate { it.copy(alwaysListeningActive = active) }
    }

    fun onError(message: String) {
        mutate { current ->
            if (current.lastError == message) current
            else current.copy(lastError = message)
        }
    }

    // ── internals ───────────────────────────────────────────────────

    private fun mapConnectionPhase(raw: String): ConnectionPhase {
        val s = raw.trim()
        return when {
            s.equals("CONNECTED", ignoreCase = true) -> ConnectionPhase.CONNECTED
            s.equals("DISCONNECTED", ignoreCase = true) -> ConnectionPhase.DISCONNECTED
            s.startsWith("Reconnecting", ignoreCase = true) -> ConnectionPhase.RECONNECTING
            s.startsWith("UNAVAILABLE", ignoreCase = true) -> ConnectionPhase.FAILED
            s.startsWith("ERROR", ignoreCase = true) -> ConnectionPhase.FAILED
            s.startsWith("Connection failed", ignoreCase = true) -> ConnectionPhase.FAILED
            else -> state.connectionPhase
        }
    }

    private fun mutate(transform: (VoiceRuntimeState) -> VoiceRuntimeState) {
        val updated = lock.withLock {
            val after = transform(state)
            if (after === state) return
            state = after.copy(updatedAt = clock.instant())
            state
        }
        listeners.forEach { it(updated) }
    }

    // ── audio device detection ──────────────────────────────────────

    data class AudioDevicePair(
        val inputs: List<AudioDeviceInfo>,
        val outputs: List<AudioDeviceInfo>
    )

    companion object {
        fun detectAudioDevices(): AudioDevicePair {
            val mixers = AudioSystem.getMixerInfo()
            val inputs = mutableListOf<AudioDeviceInfo>()
            val outputs = mutableListOf<AudioDeviceInfo>()
            for (info in mixers) {
                val mixer = AudioSystem.getMixer(info)
                val hasCapture = mixer.targetLineInfo.any { it.lineClass == TargetDataLine::class.java }
                val hasPlayback = mixer.sourceLineInfo.any { it.lineClass == SourceDataLine::class.java }
                if (hasCapture) {
                    inputs += AudioDeviceInfo(info.name, available = true)
                }
                if (hasPlayback) {
                    outputs += AudioDeviceInfo(info.name, available = true)
                }
            }
            return AudioDevicePair(inputs, outputs)
        }
    }
}
