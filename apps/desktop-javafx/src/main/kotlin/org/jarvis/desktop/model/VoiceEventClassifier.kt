package org.jarvis.desktop.model

/**
 * Pure classifier that derives user-facing voice events from state transitions.
 *
 * Compares consecutive [VoiceRuntimeState] snapshots and produces zero or more
 * [Event] records for meaningful changes. No side effects, no IO.
 *
 * Connection events (connected/disconnected/reconnecting) are intentionally
 * omitted — those are already recorded by [org.jarvis.desktop.runtime.DesktopRuntimeMonitor.consumeVoiceStatus].
 */
object VoiceEventClassifier {

    enum class Severity { INFO, SUCCESS, WARNING, ERROR }

    data class Event(
        val severity: Severity,
        val title: String,
        val details: String = ""
    )

    fun classify(previous: VoiceRuntimeState?, current: VoiceRuntimeState): List<Event> {
        if (previous == null) return emptyList()

        val events = mutableListOf<Event>()

        classifySessionTransition(previous, current)?.let { events += it }
        classifyPttRelease(previous, current)?.let { events += it }
        classifyNewError(previous, current)?.let { events += it }
        classifyDeviceLoss(previous, current)?.let { events += it }

        return events
    }

    // ── session lifecycle ────────────────────────────────────────────

    internal fun classifySessionTransition(
        prev: VoiceRuntimeState,
        curr: VoiceRuntimeState
    ): Event? {
        if (prev.sessionState == curr.sessionState) return null

        return when (curr.sessionState) {
            VoiceState.LISTENING ->
                if (curr.pushToTalkActive) Event(Severity.INFO, "Push-to-talk started")
                else Event(Severity.INFO, "Listening to command")

            VoiceState.PROCESSING ->
                Event(Severity.INFO, "Processing voice command")

            VoiceState.TTS_PLAYBACK ->
                Event(Severity.SUCCESS, "Jarvis responding")

            VoiceState.IDLE -> when (prev.sessionState) {
                VoiceState.COOLDOWN ->
                    Event(Severity.SUCCESS, "Voice session completed")
                VoiceState.LISTENING, VoiceState.PROCESSING, VoiceState.TTS_PLAYBACK ->
                    Event(Severity.INFO, "Voice session cancelled")
                VoiceState.ERROR ->
                    Event(Severity.INFO, "Voice session recovered")
                else -> null
            }

            VoiceState.COOLDOWN -> null
            VoiceState.LISTENING_WAKE_WORD -> null
            VoiceState.ERROR -> null // handled by classifyNewError
        }
    }

    // ── push-to-talk release ─────────────────────────────────────────

    internal fun classifyPttRelease(
        prev: VoiceRuntimeState,
        curr: VoiceRuntimeState
    ): Event? {
        if (prev.pushToTalkActive && !curr.pushToTalkActive
            && curr.sessionState == VoiceState.LISTENING
        ) {
            return Event(Severity.INFO, "Push-to-talk released")
        }
        return null
    }

    // ── error appearance ─────────────────────────────────────────────

    internal fun classifyNewError(
        prev: VoiceRuntimeState,
        curr: VoiceRuntimeState
    ): Event? {
        val error = curr.lastError ?: return null
        if (error == prev.lastError) return null

        val classified = VoiceUxStatus.classifyRawError(error)
        return Event(
            severity = mapUxSeverity(classified.severity),
            title = classified.headline,
            details = classified.guidance ?: ""
        )
    }

    // ── device loss ──────────────────────────────────────────────────

    internal fun classifyDeviceLoss(
        prev: VoiceRuntimeState,
        curr: VoiceRuntimeState
    ): Event? {
        if (!prev.hasUsableInput || curr.hasUsableInput) return null
        if (!curr.connectionPhase.isUsable()) return null

        val detail = curr.inputDevice?.name
            ?.let { "'$it' is no longer available" }
            ?: "No input device detected"
        return Event(Severity.WARNING, "Microphone became unavailable", detail)
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun mapUxSeverity(uxSeverity: VoiceUxStatus.Severity): Severity = when (uxSeverity) {
        VoiceUxStatus.Severity.INFO -> Severity.INFO
        VoiceUxStatus.Severity.ACTIVE -> Severity.INFO
        VoiceUxStatus.Severity.SUCCESS -> Severity.SUCCESS
        VoiceUxStatus.Severity.WARNING -> Severity.WARNING
        VoiceUxStatus.Severity.ERROR -> Severity.ERROR
    }
}
