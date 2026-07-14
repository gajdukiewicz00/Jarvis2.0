package org.jarvis.desktop.service

/**
 * Pure mapping from a [WakeWordInitOutcome] to the UI facts VoiceTab needs:
 * the toggle-button label, the always-listening flag, and the readable failure
 * dialog. Kept free of JavaFX so it is unit-testable without a UI toolkit and
 * VoiceTab can simply apply the results.
 *
 * The dialog text intentionally shows only human-readable state (ok / failed /
 * skipped / not reached) and the recommended fix — raw native message stacks
 * and error codes belong in the LOG (see [WakeWordDiagnostics.toJson]).
 */
object WakeWordOutcomeUi {

    const val START_LABEL = "Start Always Listening"
    const val STOP_LABEL = "Stop Always Listening"

    fun buttonLabel(outcome: WakeWordInitOutcome): String = when (outcome) {
        is WakeWordInitOutcome.Enabled -> STOP_LABEL
        is WakeWordInitOutcome.Disabled -> START_LABEL
    }

    fun isAlwaysListening(outcome: WakeWordInitOutcome): Boolean =
        outcome is WakeWordInitOutcome.Enabled

    /** True when we enabled on the built-in engine only because the custom model was incompatible. */
    fun shouldShowCustomFallbackNotice(outcome: WakeWordInitOutcome): Boolean =
        outcome is WakeWordInitOutcome.Enabled &&
            outcome.mode == WakeWordMode.BUILTIN_JARVIS &&
            outcome.diagnostics.customModelCompatible == false

    fun disabledDialogText(diagnostics: WakeWordDiagnostics, reason: String): String {
        val custom = when (diagnostics.customModelCompatible) {
            true -> "ok"
            false -> "failed"
            null -> "skipped"
        }
        val builtIn = when (diagnostics.builtInJarvisAvailable) {
            true -> "ok"
            false -> "failed"
            null -> "not reached"
        }
        val mic = diagnostics.selectedInputDevice
            ?: diagnostics.defaultInputDevice
            ?: diagnostics.manualTalkInputDevice
            ?: "default"
        val likely = diagnostics.recommendedFix ?: reason
        return buildString {
            append("Wake word detection failed to initialize.\n")
            append("Manual Talk still works.\n\n")
            append("Likely reason: $likely.\n")
            append("Tried:\n")
            append("- custom Russian model: $custom\n")
            append("- built-in Jarvis fallback: $builtIn\n")
            append("Selected mic: $mic\n")
            append("Recommended: ${diagnostics.recommendedFix ?: "use Manual Talk"}.")
        }
    }
}
