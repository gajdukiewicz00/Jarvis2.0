package org.jarvis.desktop.service.wake

import org.jarvis.desktop.service.AccessKeyValidation
import org.jarvis.desktop.service.AccessKeyValidationResult
import org.jarvis.desktop.service.StaticInitInfo
import org.jarvis.desktop.service.WakeWordAttemptResult
import org.jarvis.desktop.service.WakeWordInitializer
import org.jarvis.desktop.service.WakeWordInputDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises [PorcupineProvider] entirely through FAKE seams — no native
 * Porcupine, no microphone. The key-optional guarantee (never throw / never
 * block when the access key is missing or invalid) is the focus.
 */
class PorcupineProviderTest {

    private val staticInfo = StaticInitInfo(
        porcupineVersion = "4.0.0",
        osName = "Linux",
        osArch = "amd64",
        javaVersion = "21",
        manualTalkDevice = "mic",
        defaultDevice = "mic"
    )
    private val config = WakeWordConfig()

    /** A real initializer whose fake `attempt` seam succeeds → Enabled outcome. */
    private fun enabledInitializer(handle: Any = "handle"): WakeWordInitializer = WakeWordInitializer(
        accessKeyPresent = true,
        customModel = null,
        devices = listOf(WakeWordInputDevice("mic", 0)),
        attempt = { WakeWordAttemptResult.Success(handle) },
        staticInfo = staticInfo,
        accessKeyLooksValidFormat = true,
        validateAccessKey = { AccessKeyValidationResult(AccessKeyValidation.VALID, null) }
    )

    /** A real initializer that returns Disabled("access_key_missing"). */
    private fun disabledInitializer(): WakeWordInitializer = WakeWordInitializer(
        accessKeyPresent = false,
        customModel = null,
        devices = emptyList(),
        attempt = { WakeWordAttemptResult.Failure("PorcupineException", "no", emptyList(), null) },
        staticInfo = staticInfo
    )

    @Test
    fun `probeAvailable mirrors keyValid`() {
        assertTrue(PorcupineProvider(keyValid = { true }, buildInitializer = { enabledInitializer() }).probeAvailable())
        assertFalse(PorcupineProvider(keyValid = { false }, buildInitializer = { enabledInitializer() }).probeAvailable())
    }

    @Test
    fun `start with invalid key returns not started and never builds the initializer`() {
        var buildCalls = 0
        val provider = PorcupineProvider(
            keyValid = { false },
            buildInitializer = { buildCalls++; enabledInitializer() }
        )

        val result = provider.start(config) { }

        assertFalse(result.started)
        assertEquals(WakeProviderState.UNAVAILABLE, result.status)
        assertNotNull(result.reason)
        assertTrue(result.reason!!.contains("porcupine_unavailable"))
        assertEquals(0, buildCalls) // key-optional: the Porcupine engine was never touched
    }

    @Test
    fun `start with valid key and Enabled outcome starts`() {
        val provider = PorcupineProvider(keyValid = { true }, buildInitializer = { enabledInitializer() })

        val result = provider.start(config) { }

        assertTrue(result.started)
        assertEquals("porcupine", result.providerId)
        assertEquals(WakeProviderState.READY, result.status)
    }

    @Test
    fun `start with valid key but Disabled outcome surfaces the disabled reason`() {
        val provider = PorcupineProvider(keyValid = { true }, buildInitializer = { disabledInitializer() })

        val result = provider.start(config) { }

        assertFalse(result.started)
        assertEquals(WakeProviderState.UNAVAILABLE, result.status)
        assertEquals("access_key_missing", result.reason)
    }

    @Test
    fun `emitWake bridges a keyword index to a normalized WakeEvent and legacy sink`() {
        val events = mutableListOf<WakeEvent>()
        val indices = mutableListOf<Int>()
        val provider = PorcupineProvider(
            keyValid = { true },
            buildInitializer = { enabledInitializer() },
            onWake = { indices += it }
        )

        provider.start(config) { events += it }
        provider.emitWake(keywordIndex = 0, score = 1.0)

        assertEquals(1, events.size)
        assertEquals("porcupine", events.single().provider)
        assertEquals(1.0, events.single().score, 1e-9)
        assertEquals(listOf(0), indices)
    }

    @Test
    fun `never throws when keyValid itself throws`() {
        val provider = PorcupineProvider(
            keyValid = { throw IllegalStateException("boom") },
            buildInitializer = { enabledInitializer() }
        )

        assertFalse(provider.probeAvailable())
        val result = provider.start(config) { }
        assertFalse(result.started)
    }

    @Test
    fun `stop is safe with a non-detector handle`() {
        val provider = PorcupineProvider(keyValid = { true }, buildInitializer = { enabledInitializer("not-a-detector") })
        provider.start(config) { }
        provider.stop() // must not throw even though the handle is not a WakeWordDetector
        assertEquals(WakeProviderState.UNAVAILABLE, provider.status().state)
    }
}
