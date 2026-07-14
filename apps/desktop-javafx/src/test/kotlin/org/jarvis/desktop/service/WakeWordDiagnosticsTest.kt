package org.jarvis.desktop.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WakeWordDiagnosticsTest {

    private fun diagnostics() = WakeWordDiagnostics(
        porcupineVersion = "4.0.0",
        osName = "Linux",
        osArch = "amd64",
        javaVersion = "21",
        accessKeyPresent = true,
        mode = "BUILTIN_JARVIS",
        customModelPath = "/models/jarvis_ru.ppn",
        customModelExists = true,
        customModelSizeBytes = 3668,
        customModelReadable = true,
        customModelCompatible = false,
        builtInJarvisAvailable = true,
        keywordPathsCount = 0,
        sensitivitiesCount = 1,
        availableInputDevices = listOf("Mic A", "Mic B"),
        defaultInputDevice = "Mic A",
        selectedInputDevice = "Mic A",
        manualTalkInputDevice = "Mic A",
        lastInitError = "Keyword file (.ppn) belongs to a different version",
        nativeErrorCodes = listOf("00000136"),
        recommendedFix = "regenerate jarvis_ru.ppn for Porcupine 4.x or it will use built-in Jarvis"
    )

    @Test
    fun `testWakeWordSetupJson contains exactly the nine section-6 keys`() {
        val json = diagnostics().testWakeWordSetupJson()

        val expectedKeys = listOf(
            "accessKeyPresent",
            "customModelPresent",
            "customModelCompatible",
            "builtInJarvisAvailable",
            "availableInputDevices",
            "selectedInputDevice",
            "manualTalkInputDevice",
            "lastInitError",
            "recommendedFix"
        )
        expectedKeys.forEach { key ->
            assertTrue(json.contains("\"$key\""), "expected key '$key' in $json")
        }
    }

    @Test
    fun `testWakeWordSetupJson never leaks the access key value`() {
        val secret = "pv-secret-access-key-value"
        // Even if a secret somehow flowed into an error/device string, the setup
        // JSON exposes only presence booleans — never a key value field.
        val json = diagnostics().copy(
            lastInitError = null,
            recommendedFix = "set PORCUPINE_ACCESS_KEY"
        ).testWakeWordSetupJson()

        assertFalse(json.contains(secret))
        assertFalse(json.contains("accessKey\""), "no raw accessKey value field")
        assertTrue(json.contains("\"accessKeyPresent\":true"))
    }

    @Test
    fun `toJson is compact and includes the runtime environment`() {
        val json = diagnostics().toJson()

        assertTrue(json.startsWith("{") && json.endsWith("}"))
        assertTrue(json.contains("\"porcupineVersion\":\"4.0.0\""))
        assertTrue(json.contains("\"nativeErrorCodes\":[\"00000136\"]"))
        assertTrue(json.contains("\"availableInputDevices\":[\"Mic A\",\"Mic B\"]"))
    }

    @Test
    fun `json escapes quotes and newlines in error text`() {
        val json = diagnostics().copy(lastInitError = "line1\n\"quoted\"").testWakeWordSetupJson()

        assertTrue(json.contains("line1\\n\\\"quoted\\\""))
    }
}
