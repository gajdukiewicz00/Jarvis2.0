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
            append(headlineFor(reason))
            append("Manual Talk still works.\n\n")
            append("Likely reason: $likely.\n")
            append("Tried:\n")
            append("- custom Russian model: $custom\n")
            append("- built-in Jarvis fallback: $builtIn\n")
            append("Selected mic: $mic\n")
            wrongDefaultDeviceNote(diagnostics)?.let { append("\n$it\n") }
            append("\nSteps to fix:\n")
            append("1. Generate a new key in the Picovoice Console.\n")
            append("2. Set PORCUPINE_ACCESS_KEY to that key.\n")
            append("3. Select C4K or T1 as the wake-word microphone.\n")
            append("4. Restart Jarvis.\n")
            append("Recommended: ${diagnostics.recommendedFix ?: "use Manual Talk"}.")
        }
    }

    /** Cause-specific first line so the dialog names WHAT went wrong up front. */
    private fun headlineFor(reason: String): String = when (reason) {
        "access_key_invalid" ->
            "Wake word detection failed: the Porcupine access key is invalid or rejected.\n"
        "access_key_missing" ->
            "Wake word detection is unavailable: no Porcupine access key is configured.\n"
        "no_input_device", "no_working_microphone" ->
            "Wake word detection failed: no compatible microphone input device was found.\n"
        else -> "Wake word detection failed to initialize.\n"
    }

    /**
     * When the raw system default was a playback/output device (the exact bug that
     * selected "alsa_playback.java [default]" as the mic), tell the user to pick a
     * real capture device instead.
     */
    private fun wrongDefaultDeviceNote(diagnostics: WakeWordDiagnostics): String? {
        val before = diagnostics.selectedInputDeviceBeforeFilter ?: return null
        if (!WakeWordInputDevices.looksLikePlayback(before)) return null
        return "Also detected wrong default input device: $before. " +
            "Try selecting C4K or T1 as wake-word microphone."
    }
}
