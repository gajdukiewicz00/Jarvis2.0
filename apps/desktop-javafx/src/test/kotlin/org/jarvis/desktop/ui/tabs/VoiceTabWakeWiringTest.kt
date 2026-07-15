package org.jarvis.desktop.ui.tabs

import org.jarvis.desktop.service.wake.AttemptRecord
import org.jarvis.desktop.service.wake.SelectionResult
import org.jarvis.desktop.service.wake.SidecarDiagnosticsData
import org.jarvis.desktop.service.wake.WakeEvent
import org.jarvis.desktop.service.wake.WakeEventGate
import org.jarvis.desktop.service.wake.WakeProviderDiagnostics
import org.jarvis.desktop.service.wake.WakeProviderState
import org.jarvis.desktop.service.wake.WakeWordProviderSelector
import org.jarvis.desktop.service.wake.WakeWordProviderType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit coverage for the PURE, JavaFX-free wake-wiring helpers extracted onto
 * [VoiceTab]'s companion so the provider-selection decision logic is testable
 * without an FX runtime, a real sidecar, or a Porcupine key:
 *
 *  - outcome→UI mapping (SelectionResult → button/label/always-listening),
 *  - the wake callback → gate → session-start decision,
 *  - env → [org.jarvis.desktop.service.wake.WakeWordConfig] parsing + defaults,
 *  - the Section-8 aggregate diagnostics JSON assembly.
 */
class VoiceTabWakeWiringTest {

    private fun wake(): WakeEvent =
        WakeEvent(provider = "openwakeword", model = "hey_jarvis", score = 0.9, device = "mic", timestampIso = "t")

    // ── outcome → UI mapping ──────────────────────────────────────────────────

    @Test
    fun `openWakeWord selection maps to Stop Always Listening and enables wake`() {
        val selection = SelectionResult(
            selected = null,
            selectedType = WakeWordProviderType.OPENWAKEWORD,
            status = WakeProviderState.READY,
            fallbackChain = listOf(AttemptRecord("openwakeword", true, null)),
            message = WakeWordProviderSelector.messageFor(WakeWordProviderType.OPENWAKEWORD)
        )

        val ui = VoiceTab.uiOutcomeFor(selection)

        assertEquals("Stop Always Listening", ui.buttonLabel)
        assertEquals(VoiceTab.STOP_LABEL, ui.buttonLabel)
        assertTrue(ui.isAlwaysListening)
        assertTrue(ui.enableWakeSession)
        assertFalse(ui.manualOnly)
        assertTrue(ui.manualTalkAvailable)
        assertTrue(ui.statusMessage.contains("openWakeWord"), "status carries the section-9 message")
    }

    @Test
    fun `manual-only selection maps to Start Always Listening with wake off but manual on`() {
        val selection = SelectionResult(
            selected = null,
            selectedType = WakeWordProviderType.MANUAL_ONLY,
            status = WakeProviderState.FALLBACK,
            fallbackChain = emptyList(),
            message = WakeWordProviderSelector.messageFor(WakeWordProviderType.MANUAL_ONLY)
        )

        val ui = VoiceTab.uiOutcomeFor(selection)

        assertEquals("Start Always Listening", ui.buttonLabel)
        assertEquals(VoiceTab.START_LABEL, ui.buttonLabel)
        assertFalse(ui.isAlwaysListening)
        assertFalse(ui.enableWakeSession)
        assertTrue(ui.manualOnly)
        assertTrue(ui.manualTalkAvailable)
        assertEquals("Wake word unavailable. Manual Talk still works.", ui.statusMessage)
    }

    @Test
    fun `vosk fallback selection stays a Stop-labelled real detector outcome`() {
        val selection = SelectionResult(
            selected = null,
            selectedType = WakeWordProviderType.VOSK_PHRASE_SPOTTER,
            status = WakeProviderState.FALLBACK,
            fallbackChain = emptyList(),
            message = WakeWordProviderSelector.messageFor(WakeWordProviderType.VOSK_PHRASE_SPOTTER)
        )

        val ui = VoiceTab.uiOutcomeFor(selection)

        assertEquals(VoiceTab.STOP_LABEL, ui.buttonLabel)
        assertTrue(ui.isAlwaysListening)
        assertTrue(ui.statusMessage.contains("Vosk"))
    }

    @Test
    fun `manualOnlyOutcome is the safe-state fallback`() {
        val ui = VoiceTab.manualOnlyOutcome()

        assertEquals(VoiceTab.START_LABEL, ui.buttonLabel)
        assertFalse(ui.isAlwaysListening)
        assertTrue(ui.manualTalkAvailable)
        assertEquals("Wake word unavailable. Manual Talk still works.", ui.statusMessage)
    }

    // ── wake callback → gate → session-start ──────────────────────────────────

    @Test
    fun `an accepted wake starts a session and duplicates within cooldown are ignored`() {
        var accepts = 0
        var nowMs = 0L
        val gate = WakeEventGate(isBusy = { false })
        val callback = VoiceTab.buildWakeCallback(gate, { nowMs }) { accepts++ }

        callback.onWakeDetected(wake()) // t=0 → accepted → startSession
        assertEquals(1, accepts)

        nowMs = 500L
        callback.onWakeDetected(wake()) // within cooldown → ignored
        assertEquals(1, accepts)

        nowMs = 500L + WakeEventGate.MAX_COOLDOWN_MS
        callback.onWakeDetected(wake()) // cooldown elapsed → accepted again
        assertEquals(2, accepts)
    }

    @Test
    fun `a wake while busy is ignored`() {
        var accepts = 0
        var busy = true
        val gate = WakeEventGate(isBusy = { busy })
        val callback = VoiceTab.buildWakeCallback(gate, { 0L }) { accepts++ }

        callback.onWakeDetected(wake()) // busy → ignored (does not even arm cooldown)
        assertEquals(0, accepts)

        busy = false
        callback.onWakeDetected(wake()) // idle → accepted
        assertEquals(1, accepts)
    }

    // ── env → WakeWordConfig parsing ──────────────────────────────────────────

    @Test
    fun `env overrides are parsed into the wake config`() {
        val env = mapOf(
            "JARVIS_WAKE_PROVIDER" to "vosk",
            "JARVIS_WAKEWORD_URL" to "http://host:9999",
            "JARVIS_WAKEWORD_MODEL" to "jarvis",
            "JARVIS_WAKEWORD_THRESHOLD" to "0.8",
            "JARVIS_WAKEWORD_DEVICE" to "C4K"
        )

        val cfg = VoiceTab.parseWakeConfig { env[it] }

        assertEquals(WakeWordProviderType.VOSK_PHRASE_SPOTTER, cfg.type)
        assertEquals("http://host:9999", cfg.sidecarUrl)
        assertEquals("jarvis", cfg.model)
        assertEquals(0.8, cfg.threshold)
        assertEquals("C4K", cfg.device)
    }

    @Test
    fun `missing env falls back to documented defaults`() {
        val cfg = VoiceTab.parseWakeConfig { null }

        assertEquals(WakeWordProviderType.AUTO, cfg.type)
        assertEquals("http://127.0.0.1:18095", cfg.sidecarUrl)
        assertEquals("hey_jarvis", cfg.model)
        assertEquals(0.5, cfg.threshold)
        assertEquals("auto", cfg.device)
    }

    @Test
    fun `provider aliases map to the right types`() {
        assertEquals(WakeWordProviderType.AUTO, VoiceTab.parseProviderType("auto"))
        assertEquals(WakeWordProviderType.AUTO, VoiceTab.parseProviderType(null))
        assertEquals(WakeWordProviderType.OPENWAKEWORD, VoiceTab.parseProviderType("openwakeword"))
        assertEquals(WakeWordProviderType.VOSK_PHRASE_SPOTTER, VoiceTab.parseProviderType("vosk"))
        assertEquals(WakeWordProviderType.PORCUPINE, VoiceTab.parseProviderType("porcupine"))
        assertEquals(WakeWordProviderType.MANUAL_ONLY, VoiceTab.parseProviderType("manual"))
        assertEquals(WakeWordProviderType.AUTO, VoiceTab.parseProviderType("nonsense"))
    }

    // ── Section-8 aggregate diagnostics JSON ──────────────────────────────────

    @Test
    fun `diagnostics json includes selectedProvider fallbackChain rejectedDevices and lastWakeScore`() {
        val selection = SelectionResult(
            selected = null,
            selectedType = WakeWordProviderType.OPENWAKEWORD,
            status = WakeProviderState.READY,
            fallbackChain = listOf(
                AttemptRecord("openwakeword", true, null),
                AttemptRecord("vosk", false, "vosk_not_installed")
            ),
            message = WakeWordProviderSelector.messageFor(WakeWordProviderType.OPENWAKEWORD)
        )
        val providerDiags = listOf(
            WakeProviderDiagnostics(
                providerId = "openwakeword",
                installed = true,
                reachable = true,
                models = listOf("hey_jarvis"),
                listening = true,
                lastWakeScore = 0.87,
                lastWakeDetectedAt = "t-oww",
                lastError = null
            ),
            WakeProviderDiagnostics(
                providerId = "porcupine",
                installed = false,
                reachable = null,
                models = emptyList(),
                listening = false,
                lastWakeScore = null,
                lastWakeDetectedAt = null,
                lastError = null
            )
        )
        val sidecar = SidecarDiagnosticsData(
            installed = true,
            models = listOf("hey_jarvis"),
            selectedDevice = "C4K",
            listening = true,
            lastWakeScore = 0.91,
            lastWakeDetectedAt = "t-sidecar",
            lastError = null
        )
        val rejected = listOf("alsa_playback.java [default]" to "playback/output device")

        val json = VoiceTab.buildDiagnosticsJson(selection, providerDiags, sidecar, rejected)

        assertTrue(json.contains("\"selectedProvider\":\"openwakeword\""), "selectedProvider present: $json")
        assertTrue(json.contains("\"providerStatus\":\"READY\""), "providerStatus present")
        assertTrue(json.contains("\"manualTalkAvailable\":true"), "manualTalkAvailable always true")
        assertTrue(json.contains("\"fallbackChain\""), "fallbackChain present")
        assertTrue(json.contains("\"vosk_not_installed\""), "fallbackChain reason present")
        assertTrue(json.contains("\"rejectedDevices\""), "rejectedDevices present")
        assertTrue(json.contains("alsa_playback.java"), "rejected device name present")
        assertTrue(json.contains("\"lastWakeScore\":0.91"), "sidecar lastWakeScore wins: $json")
        assertTrue(json.contains("\"porcupineAvailable\":false"), "porcupine availability sourced from diag")
        assertTrue(json.contains("\"selectedInputDevice\":\"C4K\""), "selected input device from sidecar")
    }

    @Test
    fun `diagnostics json carries the honest live observability fields from the sidecar`() {
        val sidecar = SidecarDiagnosticsData(
            installed = true,
            models = listOf("hey_jarvis_v0.1"),
            selectedDevice = "C4K",
            listening = true,
            lastWakeScore = 0.9,
            lastWakeDetectedAt = "t",
            lastError = null,
            currentRms = 0.0,
            audioSignalPresent = false,
            audioFramesReceived = 1200L,
            currentScore = 0.12,
            maximumScoreLast30Seconds = 0.44,
            inferenceCount = 999L,
            threshold = 0.5,
            modelName = "hey_jarvis_v0.1",
            expectedWakePhrase = "hey jarvis",
            ready = false
        )

        val json = VoiceTab.buildDiagnosticsJson(
            selection = null,
            providerDiags = emptyList(),
            sidecarDiag = sidecar,
            rejected = emptyList()
        )

        assertTrue(json.contains("\"audioSignalPresent\":false"), "audioSignalPresent present: $json")
        assertTrue(json.contains("\"currentScore\":0.12"), "currentScore present")
        assertTrue(json.contains("\"maximumScoreLast30Seconds\":0.44"), "max score present")
        assertTrue(json.contains("\"audioFramesReceived\":1200"), "frames present")
        assertTrue(json.contains("\"inferenceCount\":999"), "inference count present")
        assertTrue(json.contains("\"expectedWakePhrase\":\"hey jarvis\""), "expected phrase present")
        assertTrue(json.contains("\"modelName\":\"hey_jarvis_v0.1\""), "model name present")
        assertTrue(json.contains("\"sidecarReady\":false"), "sidecar ready present")
    }

    @Test
    fun `diagnostics json for a null selection is still valid and manual-safe`() {
        val json = VoiceTab.buildDiagnosticsJson(
            selection = null,
            providerDiags = emptyList(),
            sidecarDiag = null,
            rejected = emptyList()
        )

        assertTrue(json.contains("\"selectedProvider\":\"manual\""), "defaults to manual: $json")
        assertTrue(json.contains("\"providerStatus\":\"UNAVAILABLE\""))
        assertTrue(json.contains("\"manualTalkAvailable\":true"))
    }
}
