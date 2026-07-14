package org.jarvis.desktop.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises the whole custom→built-in fallback decision tree of
 * [WakeWordInitializer] through a FAKE `attempt` seam — no native Porcupine and
 * no microphone. Every branch the task's section 8 lists is covered here.
 */
class WakeWordInitializerTest {

    private val staticInfo = StaticInitInfo(
        porcupineVersion = "4.0.0",
        osName = "Linux",
        osArch = "amd64",
        javaVersion = "21",
        manualTalkDevice = "default-mic",
        defaultDevice = "default-mic"
    )

    private fun device(name: String, index: Int = 0) = WakeWordInputDevice(name, index)

    private fun customModel(exists: Boolean = true, readable: Boolean = true) = CustomModelInfo(
        path = "/models/jarvis_ru.ppn",
        exists = exists,
        sizeBytes = 3668,
        readable = readable
    )

    /** Records every attempt and decides success purely by mode. */
    private class RecordingAttempt(
        private val customSucceeds: Boolean,
        private val builtInSucceeds: Boolean
    ) : (WakeWordAttempt) -> WakeWordAttemptResult {
        val seen = mutableListOf<WakeWordAttempt>()

        override fun invoke(request: WakeWordAttempt): WakeWordAttemptResult {
            seen += request
            val ok = when (request.mode) {
                WakeWordMode.CUSTOM_RU -> customSucceeds
                WakeWordMode.BUILTIN_JARVIS -> builtInSucceeds
            }
            return if (ok) {
                WakeWordAttemptResult.Success("handle:${request.mode}:${request.device?.name}")
            } else {
                WakeWordAttemptResult.Failure(
                    exceptionClass = "PorcupineException",
                    message = "Keyword file (.ppn) belongs to a different version",
                    messageStack = listOf("[0] d3ff828 00000136: init failed"),
                    nativeCode = "00000136"
                )
            }
        }
    }

    private fun initializer(
        accessKeyPresent: Boolean = true,
        customModel: CustomModelInfo? = customModel(),
        devices: List<WakeWordInputDevice> = listOf(device("mic-1", 1)),
        attempt: (WakeWordAttempt) -> WakeWordAttemptResult,
        persistDevice: (WakeWordInputDevice) -> Unit = {},
        stopProbe: (Any) -> Unit = {}
    ) = WakeWordInitializer(
        accessKeyPresent = accessKeyPresent,
        customModel = customModel,
        devices = devices,
        attempt = attempt,
        staticInfo = staticInfo,
        persistDevice = persistDevice,
        stopProbe = stopProbe
    )

    @Test
    fun `custom success yields Enabled CUSTOM_RU with compatible model`() {
        val attempt = RecordingAttempt(customSucceeds = true, builtInSucceeds = true)
        val outcome = initializer(attempt = attempt).initialize()

        val enabled = assertInstanceOf(WakeWordInitOutcome.Enabled::class.java, outcome)
        assertEquals(WakeWordMode.CUSTOM_RU, enabled.mode)
        assertEquals(true, enabled.diagnostics.customModelCompatible)
        // Built-in must NEVER be attempted once custom succeeds.
        assertFalse(attempt.seen.any { it.mode == WakeWordMode.BUILTIN_JARVIS })
    }

    @Test
    fun `custom failure triggers a built-in attempt`() {
        val attempt = RecordingAttempt(customSucceeds = false, builtInSucceeds = true)
        initializer(attempt = attempt).initialize()

        assertTrue(attempt.seen.any { it.mode == WakeWordMode.CUSTOM_RU }, "custom attempted")
        assertTrue(attempt.seen.any { it.mode == WakeWordMode.BUILTIN_JARVIS }, "built-in fallback attempted")
    }

    @Test
    fun `custom fail then built-in success yields Enabled BUILTIN_JARVIS`() {
        val attempt = RecordingAttempt(customSucceeds = false, builtInSucceeds = true)
        val outcome = initializer(attempt = attempt).initialize()

        val enabled = assertInstanceOf(WakeWordInitOutcome.Enabled::class.java, outcome)
        assertEquals(WakeWordMode.BUILTIN_JARVIS, enabled.mode)
        assertEquals(false, enabled.diagnostics.customModelCompatible)
        assertEquals(true, enabled.diagnostics.builtInJarvisAvailable)
        assertEquals(
            "regenerate jarvis_ru.ppn for Porcupine 4.x or it will use built-in Jarvis",
            enabled.diagnostics.recommendedFix
        )
    }

    @Test
    fun `custom fail and built-in fail yields Disabled all_failed`() {
        val attempt = RecordingAttempt(customSucceeds = false, builtInSucceeds = false)
        val outcome = initializer(attempt = attempt).initialize()

        val disabled = assertInstanceOf(WakeWordInitOutcome.Disabled::class.java, outcome)
        assertEquals("all_failed", disabled.reason)
        assertTrue(disabled.userMessage.contains("Manual Talk still works"))
        assertEquals(false, disabled.diagnostics.builtInJarvisAvailable)
        assertEquals(false, disabled.diagnostics.customModelCompatible)
        // Native code captured for the LOG, not lost.
        assertEquals(listOf("00000136"), disabled.diagnostics.nativeErrorCodes)
    }

    @Test
    fun `missing access key yields Disabled access_key_missing without throwing`() {
        val attempt = RecordingAttempt(customSucceeds = true, builtInSucceeds = true)
        val outcome = initializer(accessKeyPresent = false, attempt = attempt).initialize()

        val disabled = assertInstanceOf(WakeWordInitOutcome.Disabled::class.java, outcome)
        assertEquals("access_key_missing", disabled.reason)
        assertTrue(disabled.userMessage.contains("Manual Talk still works"))
        assertEquals("set PORCUPINE_ACCESS_KEY", disabled.diagnostics.recommendedFix)
        // No attempt should have been made at all.
        assertTrue(attempt.seen.isEmpty())
    }

    @Test
    fun `null custom model skips custom and falls back to built-in`() {
        val attempt = RecordingAttempt(customSucceeds = true, builtInSucceeds = true)
        val outcome = initializer(customModel = null, attempt = attempt).initialize()

        val enabled = assertInstanceOf(WakeWordInitOutcome.Enabled::class.java, outcome)
        assertEquals(WakeWordMode.BUILTIN_JARVIS, enabled.mode)
        assertFalse(attempt.seen.any { it.mode == WakeWordMode.CUSTOM_RU }, "custom never attempted")
        // customModelCompatible stays null (never tried), not false.
        assertNull(enabled.diagnostics.customModelCompatible)
    }

    @Test
    fun `unreadable custom model skips custom and falls back to built-in`() {
        val attempt = RecordingAttempt(customSucceeds = true, builtInSucceeds = true)
        val outcome = initializer(customModel = customModel(readable = false), attempt = attempt).initialize()

        val enabled = assertInstanceOf(WakeWordInitOutcome.Enabled::class.java, outcome)
        assertEquals(WakeWordMode.BUILTIN_JARVIS, enabled.mode)
        assertFalse(attempt.seen.any { it.mode == WakeWordMode.CUSTOM_RU })
    }

    @Test
    fun `empty device list yields Disabled no_input_device`() {
        val attempt = RecordingAttempt(customSucceeds = true, builtInSucceeds = true)
        val outcome = initializer(devices = emptyList(), attempt = attempt).initialize()

        val disabled = assertInstanceOf(WakeWordInitOutcome.Disabled::class.java, outcome)
        assertEquals("no_input_device", disabled.reason)
        assertTrue(disabled.userMessage.contains("Manual Talk still works"))
        assertEquals("choose another input device", disabled.diagnostics.recommendedFix)
        assertTrue(attempt.seen.isEmpty())
    }

    @Test
    fun `disabled outcome carries diagnostics and never signals an enabled handle`() {
        val attempt = RecordingAttempt(customSucceeds = false, builtInSucceeds = false)
        val outcome = initializer(attempt = attempt).initialize()

        // Proves no stale "enabled" state: the outcome is Disabled, so there is
        // no handle to adopt as a live detector.
        assertFalse(outcome is WakeWordInitOutcome.Enabled)
        val disabled = assertInstanceOf(WakeWordInitOutcome.Disabled::class.java, outcome)
        assertEquals("4.0.0", disabled.diagnostics.porcupineVersion)
    }

    @Test
    fun `persistDevice is called with the working device and preferred device is tried first`() {
        val persisted = mutableListOf<WakeWordInputDevice>()
        val attempt = RecordingAttempt(customSucceeds = true, builtInSucceeds = true)
        val devices = listOf(device("preferred-mic", 0), device("other-mic", 1))

        val outcome = initializer(
            devices = devices,
            attempt = attempt,
            persistDevice = { persisted += it }
        ).initialize()

        val enabled = assertInstanceOf(WakeWordInitOutcome.Enabled::class.java, outcome)
        assertEquals("preferred-mic", enabled.device?.name)
        // The default-first device is attempted first.
        assertEquals("preferred-mic", attempt.seen.first().device?.name)
        assertEquals(listOf(device("preferred-mic", 0)), persisted)
    }

    @Test
    fun `diagnose probes both engines and releases probe handles`() {
        val stopped = mutableListOf<Any>()
        val attempt = RecordingAttempt(customSucceeds = false, builtInSucceeds = true)

        val diagnostics = initializer(
            attempt = attempt,
            stopProbe = { stopped += it }
        ).diagnose()

        assertEquals(false, diagnostics.customModelCompatible)
        assertEquals(true, diagnostics.builtInJarvisAvailable)
        // The built-in Success handle from the dry probe must be stopped.
        assertEquals(1, stopped.size)
    }
}
