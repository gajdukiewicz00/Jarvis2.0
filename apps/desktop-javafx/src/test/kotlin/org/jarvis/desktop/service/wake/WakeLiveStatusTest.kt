package org.jarvis.desktop.service.wake

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit coverage for [WakeLiveStatus] — the PURE (JavaFX-free) mapping of sidecar
 * diagnostics + self-test / calibration outcomes to honest, user-facing strings.
 *
 * The whole point is HONEST visibility: a dead mic must read "SILENT" / warn, a
 * not-ready sidecar must NOT read "active", and a self-test only ever reports
 * success when the phrase was really detected. These tests pin exactly that.
 */
class WakeLiveStatusTest {

    private fun diag(
        audioSignalPresent: Boolean? = null,
        ready: Boolean? = null,
        currentScore: Double? = null,
        maximumScoreLast30Seconds: Double? = null,
        threshold: Double? = null,
        currentRms: Double? = null,
        modelName: String? = null,
        expectedWakePhrase: String? = null
    ): SidecarDiagnosticsData = SidecarDiagnosticsData(
        installed = true,
        models = listOf("hey_jarvis"),
        selectedDevice = "C4K",
        listening = true,
        lastWakeScore = null,
        lastWakeDetectedAt = null,
        lastError = null,
        audioSignalPresent = audioSignalPresent,
        ready = ready,
        currentScore = currentScore,
        maximumScoreLast30Seconds = maximumScoreLast30Seconds,
        threshold = threshold,
        currentRms = currentRms,
        modelName = modelName,
        expectedWakePhrase = expectedWakePhrase
    )

    // ── dead-mic warning (the C4K case) ───────────────────────────────────────

    @Test
    fun `audioSignalPresent false while listening yields the NO signal warning`() {
        val warning = WakeLiveStatus.signalWarningOrNull(diag(audioSignalPresent = false), listening = true)
        assertEquals(WakeLiveStatus.NO_SIGNAL_WARNING, warning)
        assertTrue(warning!!.contains("NO signal"))
        assertTrue(warning.contains("pick another Wake Word Microphone"))
    }

    @Test
    fun `a present signal produces no warning`() {
        assertNull(WakeLiveStatus.signalWarningOrNull(diag(audioSignalPresent = true), listening = true))
    }

    @Test
    fun `no warning when not listening even if the mic is silent`() {
        assertNull(WakeLiveStatus.signalWarningOrNull(diag(audioSignalPresent = false), listening = false))
    }

    @Test
    fun `mic signal line reflects present silent and unknown`() {
        assertEquals("mic signal: present", WakeLiveStatus.micSignalLine(diag(audioSignalPresent = true)))
        assertEquals("mic signal: SILENT", WakeLiveStatus.micSignalLine(diag(audioSignalPresent = false)))
        assertEquals("mic signal: unknown", WakeLiveStatus.micSignalLine(diag(audioSignalPresent = null)))
        assertEquals("mic signal: unknown", WakeLiveStatus.micSignalLine(null))
    }

    // ── self-test mapping (never fakes success) ───────────────────────────────

    @Test
    fun `ok self-test yields the success text with the real max score`() {
        val text = WakeLiveStatus.selfTestText(
            SelfTestResult(stage = "detected", ok = true, maxScore = 0.998, threshold = 0.5, message = "")
        )
        assertEquals("Wake phrase detected using openWakeWord. Score 0.998.", text)
        assertTrue(WakeLiveStatus.selfTestSucceeded(SelfTestResult("detected", true, 0.9, 0.5, "")))
    }

    @Test
    fun `each failing self-test stage surfaces its staged message`() {
        val noAudio = WakeLiveStatus.selfTestText(
            SelfTestResult("no_audio", false, null, 0.5, "Microphone is open but no audio signal was detected.")
        )
        assertEquals("Microphone is open but no audio signal was detected.", noAudio)

        val below = WakeLiveStatus.selfTestText(
            SelfTestResult(
                "below_threshold", false, 0.21, 0.5,
                "Audio signal present but model scores stayed below threshold (max 0.21)."
            )
        )
        assertTrue(below.contains("below threshold"))

        val noEvent = WakeLiveStatus.selfTestText(
            SelfTestResult("no_event", false, 0.7, 0.5, "Model detected the phrase but the event stream didn't deliver it.")
        )
        assertTrue(noEvent.contains("event stream didn't deliver"))
    }

    @Test
    fun `a blank message falls back to a stage-derived failure line`() {
        assertEquals(
            "Microphone is open but no audio signal was detected.",
            WakeLiveStatus.selfTestText(SelfTestResult("no_audio", false, null, 0.5, ""))
        )
        assertTrue(
            WakeLiveStatus.selfTestText(SelfTestResult("below_threshold", false, 0.2, 0.5, ""))
                .contains("below threshold")
        )
        assertTrue(
            WakeLiveStatus.selfTestText(SelfTestResult("no_event", false, 0.7, 0.5, ""))
                .contains("event stream")
        )
        // Failure never renders the success phrasing.
        assertFalse(
            WakeLiveStatus.selfTestText(SelfTestResult("no_audio", false, null, 0.5, ""))
                .contains("Wake phrase detected using")
        )
    }

    // ── honest Always-Listening status ────────────────────────────────────────

    @Test
    fun `listening status is off when always-listening is off`() {
        assertTrue(
            WakeLiveStatus.listeningStatus(diag(audioSignalPresent = true, ready = true), false, true)
                .contains("off")
        )
    }

    @Test
    fun `not-ready sidecar never reads active`() {
        val s = WakeLiveStatus.listeningStatus(diag(ready = false, audioSignalPresent = true), true, true)
        assertTrue(s.contains("NOT ready"))
        assertFalse(s.contains("active"))
    }

    @Test
    fun `silent mic while listening reads NO mic signal not active`() {
        val s = WakeLiveStatus.listeningStatus(diag(ready = true, audioSignalPresent = false), true, true)
        assertTrue(s.contains("NO mic signal"))
        assertTrue(s.contains("Hey Jarvis"))
        assertFalse(s.contains("active"))
    }

    @Test
    fun `dropped event stream is reflected honestly`() {
        val s = WakeLiveStatus.listeningStatus(
            diag(ready = true, audioSignalPresent = true), alwaysListening = true, sseConnected = false
        )
        assertTrue(s.contains("DISCONNECTED"))
        assertFalse(s.contains("active"))
    }

    @Test
    fun `a healthy listening sidecar reads active with the phrase`() {
        val s = WakeLiveStatus.listeningStatus(
            diag(ready = true, audioSignalPresent = true, expectedWakePhrase = "hey jarvis"),
            alwaysListening = true, sseConnected = true
        )
        assertTrue(s.contains("active"))
        assertTrue(s.contains("Hey Jarvis"))
    }

    // ── phrase + score + calibration lines ────────────────────────────────────

    @Test
    fun `phrase line title-cases the sidecar expected phrase`() {
        assertEquals("Say: \"Hey Jarvis\"", WakeLiveStatus.phraseLine(diag(expectedWakePhrase = "hey jarvis")))
        // Falls back to the model name humanized when no explicit phrase.
        assertEquals("Say: \"Hey Jarvis\"", WakeLiveStatus.phraseLine(diag(modelName = "hey_jarvis_v0.1")))
        // Null diag → safe default.
        assertEquals("Say: \"Hey Jarvis\"", WakeLiveStatus.phraseLine(null))
    }

    @Test
    fun `live score line shows current max and threshold`() {
        val line = WakeLiveStatus.liveScoreLine(
            diag(currentScore = 0.31, maximumScoreLast30Seconds = 0.87, threshold = 0.5)
        )
        assertTrue(line.contains("0.310"))
        assertTrue(line.contains("0.870"))
        assertTrue(line.contains("0.500"))
    }

    @Test
    fun `score progress clamps to zero-one`() {
        assertEquals(0.0, WakeLiveStatus.scoreProgress(null))
        assertEquals(0.31, WakeLiveStatus.scoreProgress(diag(currentScore = 0.31)))
        assertEquals(1.0, WakeLiveStatus.scoreProgress(diag(currentScore = 1.4)))
    }

    @Test
    fun `calibration text reports detected vs dead mic`() {
        val ok = WakeLiveStatus.calibrationText(
            CalibrationResult(device = "C4K", frameCount = 94, minRms = 0.0, avgRms = 0.03, maxRms = 0.12, signalDetected = true)
        )
        assertTrue(ok.contains("signal DETECTED"))
        assertTrue(ok.contains("C4K"))

        val dead = WakeLiveStatus.calibrationText(
            CalibrationResult(device = "C4K", frameCount = 94, minRms = 0.0, avgRms = 0.0, maxRms = 0.0, signalDetected = false)
        )
        assertTrue(dead.contains("NO signal"))
    }
}
