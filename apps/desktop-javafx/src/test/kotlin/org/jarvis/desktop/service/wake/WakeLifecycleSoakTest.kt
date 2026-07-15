package org.jarvis.desktop.service.wake

import org.jarvis.desktop.model.VoiceState
import org.jarvis.desktop.service.VoiceSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PROVIDER-AWARE soak test (DoD #13/#14): drives ≥50 sequential
 * wake → record → process → speak → complete cycles through a REAL
 * [WakeWordProviderManager] wired to a [VoiceSession] exactly the way
 * `VoiceTab` wires it — `onDisableWakeWord → manager.pause()` (pause the active
 * provider while a command records / TTS plays) and `onEnableWakeWord →
 * manager.resume()` (re-arm after recovery). After EACH command it asserts the
 * anti-leak contract (state settled to WAKE_LISTENING, recorder off, correlationId
 * cleared, always-listening still on, wake re-armed) AND the provider lifecycle
 * contract (paused DURING the command, RESUMED after completion). Pause/resume
 * counts are tracked to prove no leak (paused == resumed, provider never left
 * paused). Everything is deterministic and hardware-free — no sidecar, no mic, no
 * real Porcupine — using a fake provider (variant 1) and a real sidecar-backed
 * provider over a fake sidecar client (variant 2).
 */
class WakeLifecycleSoakTest {

    /** Number of full command lifecycles to run before the "next command still records" check. */
    private val cycles = 50

    /** The user-observed command sequence, cycled to reach the soak length. */
    private val soakCommands = listOf(
        "ты тут", "какие планы на день", "открой ютюб", "сделай громче",
        "воспроизведи", "открой терминал", "какие планы на день", "сделай тише",
        "что у нас с финансами", "покажи память про джарвис"
    )

    /**
     * Fake provider that records pause/resume/start/stop counts and its own paused
     * state — enough to prove the manager delegates and never leaks a paused provider.
     */
    private class RecordingProvider(
        override val providerId: String,
        override val type: WakeWordProviderType
    ) : WakeWordProvider {
        var startCount = 0
        var stopCount = 0
        var pauseCount = 0
        var resumeCount = 0
        @Volatile var pausedNow = false
            private set

        override fun probeAvailable(): Boolean = true

        override fun start(config: WakeWordConfig, callback: WakeWordCallback): WakeWordStartResult {
            startCount++
            pausedNow = false
            return WakeWordStartResult(true, providerId, WakeProviderState.READY, null)
        }

        override fun pause() {
            pauseCount++
            pausedNow = true
        }

        override fun resume() {
            resumeCount++
            pausedNow = false
        }

        override fun stop() {
            stopCount++
            pausedNow = false
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
            paused = pausedNow
        )
    }

    /**
     * Build a [VoiceSession] wired to [manager] the SAME way VoiceTab wires it: a
     * command start pauses the active provider, recovery resumes it. [wakeWordEnabled]
     * mirrors VoiceTab's detector-armed flag so we can assert re-arming per command.
     */
    private fun sessionFor(manager: WakeWordProviderManager, wakeWordEnabled: AtomicBoolean): VoiceSession =
        VoiceSession(
            onStateChange = { _, _ -> },
            onStartRecording = { },
            onStopRecording = { },
            onSendEndOfSpeech = { },
            onEnableWakeWord = {
                wakeWordEnabled.set(true)
                manager.resume() // belt: re-arm the active provider on recovery
            },
            onDisableWakeWord = {
                wakeWordEnabled.set(false)
                manager.pause() // belt: hold detection off while the command records / TTS plays
            },
            onPauseMedia = { },
            onResumeMedia = { },
            onSpeakTimeout = { },
            onSessionError = { _, _ -> }
        )

    /**
     * Run one deterministic full lifecycle: wake(record) → transcript(process) →
     * tts(speak start+finish) → complete. Uses the idempotent
     * [VoiceSession.completeVoiceCommandSession] chokepoint to finish the cooldown
     * synchronously (no wall-clock wait) so the soak is fast and race-free. Asserts
     * the provider is PAUSED mid-command and RESUMED (and everything settled) after.
     */
    private fun runCycle(
        i: Int,
        session: VoiceSession,
        manager: WakeWordProviderManager,
        wakeWordEnabled: AtomicBoolean
    ) {
        val phrase = soakCommands[i % soakCommands.size]
        wakeWordEnabled.set(false) // a real wake disables the detector; recovery must re-arm it

        // 1) wake → record (startSession fires onDisableWakeWord → manager.pause())
        val id = session.startSession(isManualTalk = true)
        assertNotNull(id, "command $i: startSession must succeed")
        assertEquals(VoiceState.LISTENING, session.state, "command $i: must be RECORDING")
        assertTrue(session.isRecordingActive, "command $i: recorder active while recording")
        assertTrue(manager.isPaused(), "command $i: provider must be PAUSED during recording")

        // 2) final transcript → process
        session.onFinalTranscript(phrase, id)
        assertEquals(VoiceState.PROCESSING, session.state, "command $i ('$phrase'): must reach PROCESSING")

        // 3) speak (TTS start → finish; AudioPlayer's finally always fires finish)
        session.onTtsPlaybackStarted()
        assertEquals(VoiceState.TTS_PLAYBACK, session.state, "command $i: must be SPEAKING")
        session.onTtsPlaybackFinished()
        assertEquals(VoiceState.COOLDOWN, session.state, "command $i: must be in COOLDOWN before completion")

        // 4) complete via the single idempotent chokepoint (fires onEnableWakeWord → manager.resume())
        session.completeVoiceCommandSession(id, "cycle-$i-complete")

        // Anti-leak + provider-lifecycle invariants asserted after EVERY command:
        assertEquals(VoiceState.LISTENING_WAKE_WORD, session.state, "command $i: must settle to WAKE_LISTENING")
        assertTrue(session.alwaysListeningActive, "command $i: always-listening must stay ON")
        assertFalse(session.isRecordingActive, "command $i: recorder must be OFF after completion")
        assertNull(session.currentCorrelationId, "command $i: correlationId must be cleared")
        assertTrue(wakeWordEnabled.get(), "command $i: wake detector must be re-armed")
        assertFalse(manager.isPaused(), "command $i: provider must be RESUMED after completion (not left paused)")
    }

    @Test
    @Timeout(60)
    fun `50 provider-aware lifecycles never leak the fake wake provider and command 51 still records`() {
        val provider = RecordingProvider("openwakeword", WakeWordProviderType.OPENWAKEWORD)
        val config = WakeWordConfig(type = WakeWordProviderType.AUTO)
        val selector = WakeWordProviderSelector(
            config,
            mapOf(
                WakeWordProviderType.OPENWAKEWORD to provider,
                WakeWordProviderType.MANUAL_ONLY to ManualOnlyProvider()
            )
        )
        val manager = WakeWordProviderManager(config, selector)
        val wakeWordEnabled = AtomicBoolean(true)
        val session = sessionFor(manager, wakeWordEnabled)

        // Start the provider (as VoiceTab.startAlwaysListening would) then arm the session.
        val selection = manager.start { }
        assertEquals(WakeWordProviderType.OPENWAKEWORD, selection.selectedType)
        session.enableAlwaysListening()
        assertEquals(VoiceState.LISTENING_WAKE_WORD, session.state)
        assertFalse(manager.isPaused(), "provider must not be paused before any command")

        repeat(cycles) { i -> runCycle(i, session, manager, wakeWordEnabled) }

        // Leak proof: every pause was matched by a resume, and the provider is not left paused.
        assertEquals(cycles, provider.pauseCount, "one pause per command")
        assertEquals(cycles, provider.resumeCount, "one resume per command")
        assertEquals(provider.pauseCount, provider.resumeCount, "pause/resume must balance (no leak)")
        assertFalse(manager.isPaused(), "provider must not be left paused after $cycles cycles")
        assertFalse(provider.pausedNow, "underlying provider must not be left paused")

        // Command 51 must still start recording normally (and pause the provider again).
        val next = session.startSession(isManualTalk = true)
        assertNotNull(next, "command ${cycles + 1} must still record after $cycles lifecycles")
        assertEquals(VoiceState.LISTENING, session.state)
        assertTrue(session.isRecordingActive, "command ${cycles + 1} recorder must be active")
        assertTrue(manager.isPaused(), "command ${cycles + 1}: provider paused again during recording")

        session.shutdown()
        manager.stop()
        // start(1) + never re-selected during the soak; a single stop tore it down.
        assertEquals(1, provider.startCount, "provider started exactly once (pause/resume, not restart)")
        assertEquals(1, provider.stopCount, "provider stopped exactly once (at teardown)")
    }

    @Test
    @Timeout(60)
    fun `50 lifecycles drive real sidecar pause and resume POSTs with no teardown between commands`() {
        // A real sidecar-backed provider over a FAKE sidecar client (no network, no mic):
        // proves manager.pause()/resume() reach POST /pause and POST /resume per command.
        val sidecar = FakeWakeSidecarClient()
        val provider = OpenWakeWordProvider(http = sidecar)
        val config = WakeWordConfig(type = WakeWordProviderType.AUTO)
        val selector = WakeWordProviderSelector(
            config,
            mapOf(
                WakeWordProviderType.OPENWAKEWORD to provider,
                WakeWordProviderType.MANUAL_ONLY to ManualOnlyProvider()
            )
        )
        val manager = WakeWordProviderManager(config, selector)
        val wakeWordEnabled = AtomicBoolean(true)
        val session = sessionFor(manager, wakeWordEnabled)

        val selection = manager.start { }
        assertEquals(WakeWordProviderType.OPENWAKEWORD, selection.selectedType)
        session.enableAlwaysListening()
        assertEquals(1, sidecar.startRequests.size, "engine started exactly once (streams continuously)")

        repeat(cycles) { i -> runCycle(i, session, manager, wakeWordEnabled) }

        // Each command paused then resumed the sidecar exactly once — counts balance, no leak.
        assertEquals(cycles, sidecar.pauseCalls, "one POST /pause per command")
        assertEquals(cycles, sidecar.resumeCalls, "one POST /resume per command")
        assertEquals(sidecar.pauseCalls, sidecar.resumeCalls, "sidecar pause/resume must balance")
        assertEquals(0, sidecar.stopCalls, "provider must NOT be torn down between commands")
        assertEquals(1, sidecar.startRequests.size, "engine never re-started during the soak")
        assertFalse(manager.isPaused(), "provider not left paused after $cycles cycles")

        // Command 51 still records, and only NOW (teardown) does the sidecar get stopped.
        val next = session.startSession(isManualTalk = true)
        assertNotNull(next, "command ${cycles + 1} must still record")
        assertEquals(VoiceState.LISTENING, session.state)
        assertTrue(manager.isPaused(), "command ${cycles + 1}: sidecar paused again during recording")

        session.shutdown()
        manager.stop()
        assertEquals(1, sidecar.stopCalls, "sidecar stopped exactly once at teardown")
    }
}
