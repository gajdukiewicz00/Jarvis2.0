package org.jarvis.desktop.service.wake

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Drives [WakeWordProviderManager] with FAKE providers (no sidecar / mic) to prove
 * the single-active-owner lifecycle: start stores the selected provider, a second
 * start stops the previous one first (no double-active), pause/resume delegate to
 * the active provider, stop clears it, and a late callback from a replaced provider
 * is dropped so it can never start a session against the new one.
 */
class WakeWordProviderManagerTest {

    /** Records lifecycle calls and lets the test fire wake events through the
     *  callback the manager wrapped and handed to [start]. */
    private class RecordingProvider(
        override val providerId: String,
        override val type: WakeWordProviderType
    ) : WakeWordProvider {
        var startCount = 0
        var stopCount = 0
        var pauseCount = 0
        var resumeCount = 0
        var capturedCallback: WakeWordCallback? = null
        private var pausedState = false

        override fun probeAvailable(): Boolean = true

        override fun start(config: WakeWordConfig, callback: WakeWordCallback): WakeWordStartResult {
            startCount++
            capturedCallback = callback
            return WakeWordStartResult(true, providerId, WakeProviderState.READY, null)
        }

        override fun pause() {
            pauseCount++
            pausedState = true
        }

        override fun resume() {
            resumeCount++
            pausedState = false
        }

        override fun stop() {
            stopCount++
        }

        override fun status(): WakeWordStatus = WakeWordStatus(WakeProviderState.READY, "$providerId active")

        override fun diagnostics(): WakeProviderDiagnostics = WakeProviderDiagnostics(
            providerId = providerId,
            installed = true,
            reachable = null,
            models = emptyList(),
            listening = startCount > stopCount,
            lastWakeScore = null,
            lastWakeDetectedAt = null,
            lastError = null,
            paused = pausedState
        )
    }

    private fun wake(provider: String) =
        WakeEvent(provider = provider, model = "hey_jarvis", score = 0.9, device = "mic", timestampIso = "t")

    private fun managerWith(vararg providers: Pair<WakeWordProviderType, WakeWordProvider>): Pair<WakeWordProviderManager, Map<WakeWordProviderType, WakeWordProvider>> {
        val map = providers.toMap()
        val config = WakeWordConfig(type = WakeWordProviderType.AUTO)
        val selector = WakeWordProviderSelector(config, map)
        return WakeWordProviderManager(config, selector) to map
    }

    @Test
    fun `start selects and stores the active provider`() {
        val oww = RecordingProvider("openwakeword", WakeWordProviderType.OPENWAKEWORD)
        val (manager, _) = managerWith(
            WakeWordProviderType.OPENWAKEWORD to oww,
            WakeWordProviderType.MANUAL_ONLY to ManualOnlyProvider()
        )

        val result = manager.start { }

        assertEquals(WakeWordProviderType.OPENWAKEWORD, result.selectedType)
        assertEquals("openwakeword", manager.activeProviderId())
        assertEquals(WakeWordProviderType.OPENWAKEWORD, manager.activeType())
        assertSame(result, manager.lastSelection())
        assertEquals(1, oww.startCount)
    }

    @Test
    fun `a second start stops the previous active first - no double-active`() {
        val oww = RecordingProvider("openwakeword", WakeWordProviderType.OPENWAKEWORD)
        val (manager, _) = managerWith(
            WakeWordProviderType.OPENWAKEWORD to oww,
            WakeWordProviderType.MANUAL_ONLY to ManualOnlyProvider()
        )

        manager.start { }
        assertEquals(0, oww.stopCount)

        manager.start { }
        assertEquals(2, oww.startCount)
        assertEquals(1, oww.stopCount) // previous active was stopped before re-selecting
        assertEquals("openwakeword", manager.activeProviderId())
    }

    @Test
    fun `pause and resume delegate to the active provider and track isPaused`() {
        val oww = RecordingProvider("openwakeword", WakeWordProviderType.OPENWAKEWORD)
        val (manager, _) = managerWith(
            WakeWordProviderType.OPENWAKEWORD to oww,
            WakeWordProviderType.MANUAL_ONLY to ManualOnlyProvider()
        )
        manager.start { }

        manager.pause()
        assertEquals(1, oww.pauseCount)
        assertTrue(manager.isPaused())

        manager.resume()
        assertEquals(1, oww.resumeCount)
        assertFalse(manager.isPaused())
    }

    @Test
    fun `pause and resume are no-ops when nothing is active`() {
        val (manager, _) = managerWith(
            WakeWordProviderType.OPENWAKEWORD to RecordingProvider("openwakeword", WakeWordProviderType.OPENWAKEWORD)
        )

        manager.pause() // never started → no active provider
        manager.resume()

        assertFalse(manager.isPaused())
        assertNull(manager.activeProviderId())
    }

    @Test
    fun `stop clears the active provider`() {
        val oww = RecordingProvider("openwakeword", WakeWordProviderType.OPENWAKEWORD)
        val (manager, _) = managerWith(
            WakeWordProviderType.OPENWAKEWORD to oww,
            WakeWordProviderType.MANUAL_ONLY to ManualOnlyProvider()
        )
        manager.start { }

        manager.stop()

        assertEquals(1, oww.stopCount)
        assertNull(manager.activeProviderId())
        assertNull(manager.activeType())
        assertFalse(manager.isPaused())
    }

    @Test
    fun `a stale callback from a replaced provider is ignored`() {
        val oww = RecordingProvider("openwakeword", WakeWordProviderType.OPENWAKEWORD)
        val (manager, _) = managerWith(
            WakeWordProviderType.OPENWAKEWORD to oww,
            WakeWordProviderType.MANUAL_ONLY to ManualOnlyProvider()
        )
        val received = mutableListOf<WakeEvent>()

        manager.start { received += it }
        val staleGuarded = oww.capturedCallback!! // bound to the first generation

        manager.start { received += it } // replaces the active provider (new generation)
        val freshGuarded = oww.capturedCallback!! // bound to the current generation

        staleGuarded.onWakeDetected(wake("openwakeword")) // must be DROPPED
        freshGuarded.onWakeDetected(wake("openwakeword")) // must be delivered

        assertEquals(1, received.size)
    }

    @Test
    fun `diagnostics reflect every configured provider`() {
        val oww = RecordingProvider("openwakeword", WakeWordProviderType.OPENWAKEWORD)
        val (manager, _) = managerWith(
            WakeWordProviderType.OPENWAKEWORD to oww,
            WakeWordProviderType.MANUAL_ONLY to ManualOnlyProvider()
        )
        manager.start { }

        val diags = manager.diagnostics()
        assertEquals(2, diags.size)
        assertTrue(diags.any { it.providerId == "openwakeword" })
        assertTrue(diags.any { it.providerId == "manual" })
    }

    @Test
    fun `status mirrors the selection outcome then falls back after stop`() {
        val oww = RecordingProvider("openwakeword", WakeWordProviderType.OPENWAKEWORD)
        val (manager, _) = managerWith(
            WakeWordProviderType.OPENWAKEWORD to oww,
            WakeWordProviderType.MANUAL_ONLY to ManualOnlyProvider()
        )

        val started = manager.start { }
        // openWakeWord is the AUTO primary → READY, and status now mirrors the SELECTION
        // outcome (state + message), not the provider's own bare status line.
        assertEquals(WakeProviderState.READY, started.status)
        assertEquals(WakeProviderState.READY, manager.status().state)
        assertEquals(started.message, manager.status().message)

        manager.stop()
        // No active provider → still reports the last selection message (never blank).
        assertTrue(manager.status().message.isNotBlank())
    }

    @Test
    fun `status surfaces FALLBACK for a manual last-resort selection - not a bare provider READY`() {
        // Only the manual last-resort is configured, so AUTO falls all the way through to it.
        // ManualOnlyProvider.status() reports its own READY, but the SELECTION is a FALLBACK —
        // the manager must surface FALLBACK, not the misleading bare provider READY.
        val (manager, _) = managerWith(
            WakeWordProviderType.MANUAL_ONLY to ManualOnlyProvider()
        )

        val result = manager.start { }

        assertEquals(WakeWordProviderType.MANUAL_ONLY, result.selectedType)
        assertEquals(WakeProviderState.FALLBACK, result.status)
        assertEquals(WakeProviderState.FALLBACK, manager.status().state)
    }
}
