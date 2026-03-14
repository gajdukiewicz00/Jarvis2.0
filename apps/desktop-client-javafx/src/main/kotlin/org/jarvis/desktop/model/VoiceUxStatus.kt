package org.jarvis.desktop.model

import org.jarvis.desktop.model.VoiceRuntimeState.ConnectionPhase

/**
 * Pure function that computes a single user-facing status line from [VoiceRuntimeState].
 *
 * Priority order: classified error > connection problem > device problem > session state.
 * Every branch produces a [StatusLine] with headline, severity, and optional guidance.
 */
object VoiceUxStatus {

    enum class Severity { INFO, ACTIVE, SUCCESS, WARNING, ERROR }

    data class StatusLine(
        val headline: String,
        val severity: Severity,
        val guidance: String? = null
    )

    fun compute(state: VoiceRuntimeState): StatusLine {
        classifyError(state)?.let { return it }

        connectionProblem(state.connectionPhase)?.let { return it }

        deviceProblem(state)?.let { return it }

        return sessionStatus(state)
    }

    // ── error classification ────────────────────────────────────────

    internal fun classifyError(state: VoiceRuntimeState): StatusLine? {
        val error = state.lastError ?: return null
        return classifyRawError(error)
    }

    fun classifyRawError(error: String): StatusLine {
        val lower = error.lowercase()
        return when {
            "permission" in lower || "access denied" in lower ->
                StatusLine(
                    "Microphone permission denied",
                    Severity.ERROR,
                    "Grant microphone access in system settings"
                )

            ("device" in lower && "busy" in lower) || "line unavailable" in lower ->
                StatusLine(
                    "Audio device busy",
                    Severity.ERROR,
                    "Close other applications using the microphone"
                )

            "microphone" in lower || "no line matching" in lower || "capture" in lower ->
                StatusLine(
                    "Microphone unavailable",
                    Severity.ERROR,
                    "Check audio settings or connect a microphone"
                )

            "timeout" in lower || "timed out" in lower ->
                StatusLine(
                    "Voice recognition timed out",
                    Severity.WARNING,
                    "Try speaking again or check your connection"
                )

            "audio recording error" in lower ->
                StatusLine(
                    "Audio recording failed",
                    Severity.ERROR,
                    "Check microphone connection and try again"
                )

            "websocket" in lower || "connection" in lower || "socket" in lower ->
                StatusLine(
                    "Voice connection failed",
                    Severity.ERROR,
                    "Check if the voice gateway is running"
                )

            else ->
                StatusLine("Voice error: $error", Severity.WARNING)
        }
    }

    // ── connection problems ─────────────────────────────────────────

    internal fun connectionProblem(phase: ConnectionPhase): StatusLine? = when (phase) {
        ConnectionPhase.DISCONNECTED ->
            StatusLine("Voice gateway disconnected", Severity.WARNING, "Check if the backend is running")
        ConnectionPhase.FAILED ->
            StatusLine("Voice gateway connection failed", Severity.ERROR, "Restart the application or check network")
        ConnectionPhase.RECONNECTING ->
            StatusLine("Reconnecting to voice gateway...", Severity.WARNING)
        ConnectionPhase.CONNECTING ->
            StatusLine("Connecting to voice gateway...", Severity.INFO)
        ConnectionPhase.CONNECTED -> null
    }

    // ── device problems ─────────────────────────────────────────────

    internal fun deviceProblem(state: VoiceRuntimeState): StatusLine? {
        if (!state.connectionPhase.isUsable()) return null
        val input = state.inputDevice
        if (input == null) {
            return StatusLine(
                "No microphone detected",
                Severity.WARNING,
                "Plug in a microphone or check system audio settings"
            )
        }
        if (!input.available) {
            return StatusLine(
                "Microphone '${input.name}' not available",
                Severity.WARNING,
                "Select a different input device or reconnect"
            )
        }
        return null
    }

    // ── normal session status ───────────────────────────────────────

    internal fun sessionStatus(state: VoiceRuntimeState): StatusLine = when (state.sessionState) {
        VoiceState.IDLE ->
            StatusLine(
                "Voice inactive",
                Severity.INFO,
                if (!state.alwaysListeningActive) "Enable always-listening or use push-to-talk" else null
            )
        VoiceState.LISTENING_WAKE_WORD ->
            StatusLine("Listening for wake word...", Severity.INFO)
        VoiceState.LISTENING ->
            if (state.pushToTalkActive)
                StatusLine("Recording (push-to-talk)...", Severity.ACTIVE)
            else
                StatusLine("Listening to your command...", Severity.ACTIVE)
        VoiceState.PROCESSING ->
            StatusLine("Processing your request...", Severity.ACTIVE)
        VoiceState.TTS_PLAYBACK ->
            StatusLine("Jarvis is speaking...", Severity.SUCCESS)
        VoiceState.COOLDOWN ->
            StatusLine("Cooldown — resuming shortly...", Severity.INFO)
        VoiceState.ERROR ->
            StatusLine("Voice error", Severity.ERROR)
    }
}
