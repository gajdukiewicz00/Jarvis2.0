package org.jarvis.desktop.service.wake

import java.util.Locale

/**
 * Pure, JavaFX-free mapping of sidecar wake diagnostics + self-test / calibration outcomes
 * to honest, user-facing observability strings for the Voice tab.
 *
 * The whole point is HONEST visibility into whether the model is actually reacting: a dead
 * mic must read "SILENT", a not-ready sidecar must not read "active", and a self-test only
 * ever reports success when the phrase was really detected AND delivered. None of these
 * functions touch JavaFX, the network, or the clock — each is a deterministic function of
 * its inputs, so the entire mapping is unit-testable without a UI or a real sidecar.
 */
object WakeLiveStatus {

    /** Shown while listening when the selected mic is producing no signal (the dead-C4K case). */
    const val NO_SIGNAL_WARNING =
        "Microphone selected but NO signal (dead/muted mic) — pick another Wake Word Microphone."

    /** The engine label used in user-facing status (the sidecar is the openWakeWord engine). */
    private const val ENGINE_LABEL = "openWakeWord"

    /** Prominent active wake phrase, e.g. `Say: "Hey Jarvis"`. Falls back to model → default. */
    fun phraseLine(diag: SidecarDiagnosticsData?): String = "Say: \"${activePhrase(diag)}\""

    /** Model + threshold summary for the live panel. */
    fun modelThresholdLine(diag: SidecarDiagnosticsData?): String {
        val model = diag?.modelName?.takeIf { it.isNotBlank() } ?: "unknown"
        return "model: $model   threshold: ${fmt(diag?.threshold)}"
    }

    /** "mic signal: present" / "mic signal: SILENT" / "mic signal: unknown". */
    fun micSignalLine(diag: SidecarDiagnosticsData?): String = when (diag?.audioSignalPresent) {
        true -> "mic signal: present"
        false -> "mic signal: SILENT"
        null -> "mic signal: unknown"
    }

    /** Compact live score line: current score + 30s-max vs threshold. */
    fun liveScoreLine(diag: SidecarDiagnosticsData?): String =
        "score: ${fmt(diag?.currentScore)}   max(30s): ${fmt(diag?.maximumScoreLast30Seconds)}   " +
            "threshold: ${fmt(diag?.threshold)}"

    /** Raw mic level + signal verdict, so the user sees input even before the model scores. */
    fun rmsLine(diag: SidecarDiagnosticsData?): String =
        "rms: ${fmt(diag?.currentRms)}   ${micSignalLine(diag)}"

    /** The dead/muted-mic warning while listening, else null. */
    fun signalWarningOrNull(diag: SidecarDiagnosticsData?, listening: Boolean): String? =
        if (listening && diag?.audioSignalPresent == false) NO_SIGNAL_WARNING else null

    /** currentScore clamped to 0..1, for an optional progress bar. Null diag → 0. */
    fun scoreProgress(diag: SidecarDiagnosticsData?): Double =
        (diag?.currentScore ?: 0.0).coerceIn(0.0, 1.0)

    /**
     * Honest Always-Listening status line. Never claims "active" when the sidecar is not
     * ready, reports no mic signal, or the wake event stream has dropped — the whole reason
     * the old bare "listening" was misleading (a dead mic still showed "listening").
     */
    fun listeningStatus(
        diag: SidecarDiagnosticsData?,
        alwaysListening: Boolean,
        sseConnected: Boolean
    ): String {
        if (!alwaysListening) return "Always Listening: off. Manual Talk still works."
        val phrase = activePhrase(diag)
        if (diag == null) return "Always Listening: $ENGINE_LABEL starting — no sidecar diagnostics yet."
        if (diag.ready == false) {
            return "Always Listening: $ENGINE_LABEL started but NOT ready — say '$phrase' won't work yet."
        }
        if (diag.audioSignalPresent == false) {
            return "Always Listening: $ENGINE_LABEL started but NO mic signal — " +
                "say '$phrase' won't work until you pick a live mic."
        }
        if (!sseConnected) {
            return "Always Listening: $ENGINE_LABEL running but the wake event stream is DISCONNECTED — reconnecting."
        }
        return "Always Listening: $ENGINE_LABEL active — say '$phrase'."
    }

    /**
     * Self-test outcome → user text. Success text (with the real max score) is returned ONLY
     * when [SelfTestResult.ok]; every failure surfaces the staged message so the user learns
     * exactly where it broke. Never fakes success.
     */
    fun selfTestText(result: SelfTestResult): String =
        if (result.ok) {
            "Wake phrase detected using $ENGINE_LABEL. Score ${fmt(result.maxScore)}."
        } else {
            result.message.ifBlank { stageFallback(result.stage, result.maxScore) }
        }

    /** True only for a passed self-test — drives success (green) vs failure (warn) styling. */
    fun selfTestSucceeded(result: SelfTestResult): Boolean = result.ok

    /** Calibration RMS summary + signal verdict. */
    fun calibrationText(result: CalibrationResult): String {
        val verdict = if (result.signalDetected) "signal DETECTED" else "NO signal (dead/muted mic)"
        return "Calibrated ${result.device ?: "selected mic"}: " +
            "min ${fmt(result.minRms)} / avg ${fmt(result.avgRms)} / max ${fmt(result.maxRms)} RMS " +
            "over ${result.frameCount} frames — $verdict."
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /** Best-known wake phrase, title-cased: expectedWakePhrase → humanized model → "Hey Jarvis". */
    private fun activePhrase(diag: SidecarDiagnosticsData?): String {
        val raw = diag?.expectedWakePhrase?.takeIf { it.isNotBlank() }
            ?: diag?.modelName?.takeIf { it.isNotBlank() }?.let { humanizeModel(it) }
            ?: "hey jarvis"
        return titleCase(raw)
    }

    /** "hey_jarvis_v0.1" → "hey jarvis": underscores→spaces, drop trailing version tokens. */
    private fun humanizeModel(model: String): String =
        model.split('_', ' ')
            .filter { it.isNotBlank() && !it.matches(Regex("v\\d.*")) }
            .joinToString(" ")
            .ifBlank { model }

    private fun titleCase(phrase: String): String =
        phrase.trim().split(Regex("\\s+")).joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        }

    /** Fallback staged messages when the sidecar sends a blank message but a known stage. */
    private fun stageFallback(stage: String, maxScore: Double?): String = when (stage.trim().lowercase()) {
        "no_audio", "silent", "mic", "no_signal", "no-signal" ->
            "Microphone is open but no audio signal was detected."
        "below_threshold", "below-threshold", "low_score", "scored" ->
            "Audio signal present but model scores stayed below threshold (max ${fmt(maxScore)})."
        "no_event", "no-event", "no_stream", "event" ->
            "Model detected the phrase but the event stream didn't deliver it."
        else -> "Wake self-test failed at stage '$stage'."
    }

    /** Short, locale-stable number format; null → "n/a". */
    private fun fmt(v: Double?): String = if (v == null) "n/a" else String.format(Locale.US, "%.3f", v)
}
