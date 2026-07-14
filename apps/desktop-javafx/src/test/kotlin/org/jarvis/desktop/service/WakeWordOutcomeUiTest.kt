package org.jarvis.desktop.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure mapping of a [WakeWordInitOutcome] to the toggle-button label,
 * always-listening flag, and the readable failure dialog. This is the
 * lightweight, non-JavaFX substitute for a VoiceTab-level button-reset test:
 * VoiceTab applies exactly these values, so verifying the mapping proves the
 * button returns to "Start Always Listening" after a Disabled outcome.
 */
class WakeWordOutcomeUiTest {

    private fun diagnostics(
        customModelCompatible: Boolean? = null,
        builtInJarvisAvailable: Boolean? = null,
        selectedInputDevice: String? = "Mic A",
        recommendedFix: String? = null
    ) = WakeWordDiagnostics(
        porcupineVersion = "4.0.0",
        osName = "Linux",
        osArch = "amd64",
        javaVersion = "21",
        accessKeyPresent = true,
        mode = "NONE",
        customModelPath = null,
        customModelExists = false,
        customModelSizeBytes = 0,
        customModelReadable = false,
        customModelCompatible = customModelCompatible,
        builtInJarvisAvailable = builtInJarvisAvailable,
        keywordPathsCount = 0,
        sensitivitiesCount = 0,
        availableInputDevices = listOf("Mic A"),
        defaultInputDevice = "Mic A",
        selectedInputDevice = selectedInputDevice,
        manualTalkInputDevice = "Mic A",
        lastInitError = null,
        nativeErrorCodes = emptyList(),
        recommendedFix = recommendedFix
    )

    private fun enabled(mode: WakeWordMode, customModelCompatible: Boolean?) =
        WakeWordInitOutcome.Enabled(
            handle = "handle",
            mode = mode,
            device = WakeWordInputDevice("Mic A", 0),
            diagnostics = diagnostics(customModelCompatible = customModelCompatible, builtInJarvisAvailable = true)
        )

    private fun disabled(reason: String, recommendedFix: String?) =
        WakeWordInitOutcome.Disabled(
            reason = reason,
            userMessage = "Wake word unavailable. Manual Talk still works.",
            diagnostics = diagnostics(
                customModelCompatible = false,
                builtInJarvisAvailable = false,
                recommendedFix = recommendedFix
            )
        )

    @Test
    fun `enabled maps to Stop label and always-listening on`() {
        val outcome = enabled(WakeWordMode.CUSTOM_RU, customModelCompatible = true)

        assertEquals("Stop Always Listening", WakeWordOutcomeUi.buttonLabel(outcome))
        assertTrue(WakeWordOutcomeUi.isAlwaysListening(outcome))
    }

    @Test
    fun `disabled maps to Start label and always-listening off`() {
        val outcome = disabled("all_failed", recommendedFix = "check Porcupine access key validity")

        assertEquals("Start Always Listening", WakeWordOutcomeUi.buttonLabel(outcome))
        assertFalse(WakeWordOutcomeUi.isAlwaysListening(outcome))
    }

    @Test
    fun `custom fallback notice only when built-in ran because custom was incompatible`() {
        assertTrue(
            WakeWordOutcomeUi.shouldShowCustomFallbackNotice(
                enabled(WakeWordMode.BUILTIN_JARVIS, customModelCompatible = false)
            )
        )
        assertFalse(
            WakeWordOutcomeUi.shouldShowCustomFallbackNotice(
                enabled(WakeWordMode.BUILTIN_JARVIS, customModelCompatible = null)
            )
        )
        assertFalse(
            WakeWordOutcomeUi.shouldShowCustomFallbackNotice(
                enabled(WakeWordMode.CUSTOM_RU, customModelCompatible = true)
            )
        )
    }

    @Test
    fun `disabled dialog text is readable and mentions Manual Talk and tried engines`() {
        val outcome = disabled("all_failed", recommendedFix = "check Porcupine access key validity")

        val text = WakeWordOutcomeUi.disabledDialogText(outcome.diagnostics, outcome.reason)

        assertTrue(text.contains("Manual Talk still works"))
        assertTrue(text.contains("custom Russian model: failed"))
        assertTrue(text.contains("built-in Jarvis fallback: failed"))
        assertTrue(text.contains("Selected mic: Mic A"))
        assertTrue(text.contains("Recommended: check Porcupine access key validity"))
        // Never surfaces raw native codes in the dialog body.
        assertFalse(text.contains("00000136"))
    }

    @Test
    fun `disabled dialog reports skipped and not-reached states`() {
        val diag = diagnostics(
            customModelCompatible = null,
            builtInJarvisAvailable = null,
            recommendedFix = "set PORCUPINE_ACCESS_KEY"
        )

        val text = WakeWordOutcomeUi.disabledDialogText(diag, "access_key_missing")

        assertTrue(text.contains("custom Russian model: skipped"))
        assertTrue(text.contains("built-in Jarvis fallback: not reached"))
    }

    @Test
    fun `access_key_invalid dialog emphasizes the key and lists the four fix steps`() {
        val diag = diagnostics(recommendedFix = "generate a new key in Picovoice Console and set PORCUPINE_ACCESS_KEY")

        val text = WakeWordOutcomeUi.disabledDialogText(diag, "access_key_invalid")

        assertTrue(text.contains("access key is invalid or rejected"))
        assertTrue(text.contains("Manual Talk still works"))
        assertTrue(text.contains("1. Generate a new key"))
        assertTrue(text.contains("2. Set PORCUPINE_ACCESS_KEY"))
        assertTrue(text.contains("3. Select C4K or T1"))
        assertTrue(text.contains("4. Restart Jarvis"))
    }

    @Test
    fun `dialog flags a playback default input device`() {
        val diag = diagnostics().copy(
            selectedInputDeviceBeforeFilter = "alsa_playback.java [default]"
        )

        val text = WakeWordOutcomeUi.disabledDialogText(diag, "no_working_microphone")

        assertTrue(text.contains("wrong default input device: alsa_playback.java [default]"))
        assertTrue(text.contains("Try selecting C4K or T1"))
    }

    @Test
    fun `dialog omits the playback note for a real default input device`() {
        val diag = diagnostics().copy(selectedInputDeviceBeforeFilter = "C4K Microphone")

        val text = WakeWordOutcomeUi.disabledDialogText(diag, "no_working_microphone")

        assertFalse(text.contains("wrong default input device"))
    }
}
