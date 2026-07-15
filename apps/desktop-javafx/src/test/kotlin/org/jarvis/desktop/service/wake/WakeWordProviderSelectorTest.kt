package org.jarvis.desktop.service.wake

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Drives [WakeWordProviderSelector] with FAKE providers to prove the AUTO chain
 * order, the Porcupine key-gating (an invalid key never blocks reaching
 * openWakeWord/Vosk), the manual last-resort, and the recorded fallback chain.
 */
class WakeWordProviderSelectorTest {

    /** Configurable fake provider; records how often start() ran. */
    private class FakeProvider(
        override val providerId: String,
        override val type: WakeWordProviderType,
        private val probe: Boolean = true,
        private val result: (WakeWordConfig, WakeWordCallback) -> WakeWordStartResult
    ) : WakeWordProvider {
        var startCount = 0
        var probeCount = 0
        var lastCallback: WakeWordCallback? = null

        override fun probeAvailable(): Boolean {
            probeCount++
            return probe
        }

        override fun start(config: WakeWordConfig, callback: WakeWordCallback): WakeWordStartResult {
            startCount++
            lastCallback = callback
            return result(config, callback)
        }

        override fun pause() {}
        override fun resume() {}
        override fun stop() {}
        override fun status(): WakeWordStatus = WakeWordStatus(WakeProviderState.READY, "")
        override fun diagnostics(): WakeProviderDiagnostics =
            WakeProviderDiagnostics(providerId, null, null, emptyList(), false, null, null, null)
    }

    private fun starting(id: String, type: WakeWordProviderType, probe: Boolean = true) =
        FakeProvider(id, type, probe) { _, _ -> WakeWordStartResult(true, id, WakeProviderState.READY, null) }

    private fun failing(id: String, type: WakeWordProviderType, reason: String, probe: Boolean = true) =
        FakeProvider(id, type, probe) { _, _ -> WakeWordStartResult(false, id, WakeProviderState.UNAVAILABLE, reason) }

    private val noopCallback = WakeWordCallback { }

    @Test
    fun `AUTO selects openWakeWord when its start succeeds`() {
        val oww = starting("openwakeword", WakeWordProviderType.OPENWAKEWORD)
        val selector = WakeWordProviderSelector(
            WakeWordConfig(type = WakeWordProviderType.AUTO),
            mapOf(
                WakeWordProviderType.OPENWAKEWORD to oww,
                WakeWordProviderType.VOSK_PHRASE_SPOTTER to starting("vosk", WakeWordProviderType.VOSK_PHRASE_SPOTTER),
                WakeWordProviderType.MANUAL_ONLY to ManualOnlyProvider()
            )
        )

        val selection = selector.select(noopCallback)

        assertEquals(WakeWordProviderType.OPENWAKEWORD, selection.selectedType)
        assertEquals(WakeProviderState.READY, selection.status)
        assertTrue(selection.message.contains("openWakeWord"))
        assertEquals(1, oww.startCount)
    }

    @Test
    fun `AUTO falls back to Vosk when openWakeWord start fails`() {
        val oww = failing("openwakeword", WakeWordProviderType.OPENWAKEWORD, "sidecar_unreachable")
        val vosk = starting("vosk", WakeWordProviderType.VOSK_PHRASE_SPOTTER)
        val selector = WakeWordProviderSelector(
            WakeWordConfig(type = WakeWordProviderType.AUTO),
            mapOf(
                WakeWordProviderType.OPENWAKEWORD to oww,
                WakeWordProviderType.VOSK_PHRASE_SPOTTER to vosk,
                WakeWordProviderType.MANUAL_ONLY to ManualOnlyProvider()
            )
        )

        val selection = selector.select(noopCallback)

        assertEquals(WakeWordProviderType.VOSK_PHRASE_SPOTTER, selection.selectedType)
        assertEquals(WakeProviderState.FALLBACK, selection.status)
        assertTrue(selection.message.contains("Vosk"))
        assertEquals(1, oww.startCount)
        assertEquals(1, vosk.startCount)
    }

    @Test
    fun `AUTO does not require a Porcupine key - manual wins and porcupine is skipped`() {
        val porcupine = starting("porcupine", WakeWordProviderType.PORCUPINE, probe = false)
        val selector = WakeWordProviderSelector(
            WakeWordConfig(type = WakeWordProviderType.AUTO),
            mapOf(
                WakeWordProviderType.OPENWAKEWORD to failing("openwakeword", WakeWordProviderType.OPENWAKEWORD, "sidecar_unreachable"),
                WakeWordProviderType.VOSK_PHRASE_SPOTTER to failing("vosk", WakeWordProviderType.VOSK_PHRASE_SPOTTER, "vosk_not_installed"),
                WakeWordProviderType.PORCUPINE to porcupine,
                WakeWordProviderType.MANUAL_ONLY to ManualOnlyProvider()
            )
        )

        val selection = selector.select(noopCallback)

        assertEquals(WakeWordProviderType.MANUAL_ONLY, selection.selectedType)
        assertNotNull(selection.selected)
        assertEquals("Wake word unavailable. Manual Talk still works.", selection.message)
        assertEquals(0, porcupine.startCount) // skipped by probe — an invalid key never blocks
        // The chain records the porcupine SKIP, not a start attempt.
        val porcupineAttempt = selection.fallbackChain.single { it.providerId == "porcupine" }
        assertFalse(porcupineAttempt.started)
        assertEquals(WakeWordProviderSelector.PORCUPINE_SKIP_REASON, porcupineAttempt.reason)
    }

    @Test
    fun `AUTO with openWakeWord available and invalid Porcupine key selects openWakeWord`() {
        val porcupine = starting("porcupine", WakeWordProviderType.PORCUPINE, probe = false)
        val selector = WakeWordProviderSelector(
            WakeWordConfig(type = WakeWordProviderType.AUTO),
            mapOf(
                WakeWordProviderType.OPENWAKEWORD to starting("openwakeword", WakeWordProviderType.OPENWAKEWORD),
                WakeWordProviderType.VOSK_PHRASE_SPOTTER to starting("vosk", WakeWordProviderType.VOSK_PHRASE_SPOTTER),
                WakeWordProviderType.PORCUPINE to porcupine,
                WakeWordProviderType.MANUAL_ONLY to ManualOnlyProvider()
            )
        )

        val selection = selector.select(noopCallback)

        assertEquals(WakeWordProviderType.OPENWAKEWORD, selection.selectedType)
        assertEquals(0, porcupine.startCount) // never reached; invalid key irrelevant
    }

    @Test
    fun `all providers fail leaves ManualOnly selected`() {
        val selector = WakeWordProviderSelector(
            WakeWordConfig(type = WakeWordProviderType.AUTO),
            mapOf(
                WakeWordProviderType.OPENWAKEWORD to failing("openwakeword", WakeWordProviderType.OPENWAKEWORD, "sidecar_unreachable"),
                WakeWordProviderType.VOSK_PHRASE_SPOTTER to failing("vosk", WakeWordProviderType.VOSK_PHRASE_SPOTTER, "vosk_not_installed"),
                WakeWordProviderType.PORCUPINE to failing("porcupine", WakeWordProviderType.PORCUPINE, "porcupine_unavailable", probe = false),
                WakeWordProviderType.MANUAL_ONLY to ManualOnlyProvider()
            )
        )

        val selection = selector.select(noopCallback)

        assertEquals(WakeWordProviderType.MANUAL_ONLY, selection.selectedType)
        assertEquals(WakeProviderState.FALLBACK, selection.status)
        assertEquals("Wake word unavailable. Manual Talk still works.", selection.message)
    }

    @Test
    fun `fallbackChain records every attempt with a reason`() {
        val selector = WakeWordProviderSelector(
            WakeWordConfig(type = WakeWordProviderType.AUTO),
            mapOf(
                WakeWordProviderType.OPENWAKEWORD to failing("openwakeword", WakeWordProviderType.OPENWAKEWORD, "sidecar_unreachable"),
                WakeWordProviderType.VOSK_PHRASE_SPOTTER to starting("vosk", WakeWordProviderType.VOSK_PHRASE_SPOTTER),
                WakeWordProviderType.MANUAL_ONLY to ManualOnlyProvider()
            )
        )

        val chain = selector.select(noopCallback).fallbackChain

        assertEquals(2, chain.size)
        assertEquals("openwakeword", chain[0].providerId)
        assertFalse(chain[0].started)
        assertEquals("sidecar_unreachable", chain[0].reason)
        assertEquals("vosk", chain[1].providerId)
        assertTrue(chain[1].started)
    }

    @Test
    fun `explicit non-AUTO type is tried alone then falls through to manual on failure`() {
        val selector = WakeWordProviderSelector(
            WakeWordConfig(type = WakeWordProviderType.OPENWAKEWORD),
            mapOf(
                WakeWordProviderType.OPENWAKEWORD to failing("openwakeword", WakeWordProviderType.OPENWAKEWORD, "sidecar_unreachable"),
                WakeWordProviderType.MANUAL_ONLY to ManualOnlyProvider()
            )
        )

        val selection = selector.select(noopCallback)

        assertEquals(WakeWordProviderType.MANUAL_ONLY, selection.selectedType)
        assertEquals(WakeProviderState.FALLBACK, selection.status)
    }

    @Test
    fun `explicit AUTO with only openWakeWord configured and it starts is READY`() {
        val selector = WakeWordProviderSelector(
            WakeWordConfig(type = WakeWordProviderType.OPENWAKEWORD),
            mapOf(
                WakeWordProviderType.OPENWAKEWORD to starting("openwakeword", WakeWordProviderType.OPENWAKEWORD),
                WakeWordProviderType.MANUAL_ONLY to ManualOnlyProvider()
            )
        )

        val selection = selector.select(noopCallback)

        assertEquals(WakeWordProviderType.OPENWAKEWORD, selection.selectedType)
        assertEquals(WakeProviderState.READY, selection.status)
    }

    @Test
    fun `the selected provider receives the wake callback`() {
        val oww = starting("openwakeword", WakeWordProviderType.OPENWAKEWORD)
        val callback = WakeWordCallback { }
        val selector = WakeWordProviderSelector(
            WakeWordConfig(type = WakeWordProviderType.AUTO),
            mapOf(
                WakeWordProviderType.OPENWAKEWORD to oww,
                WakeWordProviderType.MANUAL_ONLY to ManualOnlyProvider()
            )
        )

        selector.select(callback)

        assertEquals(callback, oww.lastCallback)
    }

    @Test
    fun `providerDiagnostics aggregates every configured provider`() {
        val selector = WakeWordProviderSelector(
            WakeWordConfig(type = WakeWordProviderType.AUTO),
            mapOf(
                WakeWordProviderType.OPENWAKEWORD to starting("openwakeword", WakeWordProviderType.OPENWAKEWORD),
                WakeWordProviderType.MANUAL_ONLY to ManualOnlyProvider()
            )
        )

        val diagnostics = selector.providerDiagnostics()

        assertEquals(2, diagnostics.size)
        assertTrue(diagnostics.any { it.providerId == "openwakeword" })
        assertTrue(diagnostics.any { it.providerId == "manual" })
    }
}
