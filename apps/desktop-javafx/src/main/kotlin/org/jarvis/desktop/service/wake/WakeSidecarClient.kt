package org.jarvis.desktop.service.wake

import java.io.Closeable

/** Health of the local wake-word sidecar. */
data class SidecarHealth(val up: Boolean, val engine: String? = null, val detail: String? = null)

/** Body for `POST /start`: which engine + capture parameters to run. */
data class StartEngineRequest(
    val device: String,
    val model: String,
    val threshold: Double,
    val engine: String
)

/**
 * Result of `POST /start`. [statusCode] + [error] let a provider distinguish a
 * hard "not installed" (503 / {"error":"vosk_not_installed"}) from a generic
 * failure without inspecting HTTP internals.
 */
data class StartEngineResponse(
    val started: Boolean,
    val statusCode: Int,
    val error: String? = null
)

/** Parsed `GET /diagnostics` payload from the sidecar. */
data class SidecarDiagnosticsData(
    val installed: Boolean?,
    val models: List<String>,
    val selectedDevice: String?,
    val listening: Boolean,
    val lastWakeScore: Double?,
    val lastWakeDetectedAt: String?,
    val lastError: String?,
    /** Sidecar-reported pause state (`paused` in /diagnostics), null when absent. */
    val paused: Boolean? = null,
    // ── Honest audio/inference observability. Every field is default-null so an older
    //    sidecar that omits them still parses cleanly; the UI treats null as "unknown". ──
    /** RMS of the most recent captured audio frame — how loud the mic actually is. */
    val currentRms: Double? = null,
    /** True when a real (non-silent) mic signal is being received. False = dead/muted mic. */
    val audioSignalPresent: Boolean? = null,
    /** How many audio frames the sidecar has pulled from the mic since start. */
    val audioFramesReceived: Long? = null,
    /** The model's score on the most recent inference — the live "is it reacting?" number. */
    val currentScore: Double? = null,
    /** Highest score seen in the last 30 seconds — catches a brief spike the poll missed. */
    val maximumScoreLast30Seconds: Double? = null,
    /** How many inferences the model has run — proves the pipeline is actually turning over. */
    val inferenceCount: Long? = null,
    /** The score threshold a wake must exceed. */
    val threshold: Double? = null,
    /** Loaded model name (e.g. "hey_jarvis_v0.1"). */
    val modelName: String? = null,
    /** The phrase the loaded model actually listens for (e.g. "hey jarvis"). */
    val expectedWakePhrase: String? = null,
    /** True when the sidecar is fully initialized and able to detect (not just "process up"). */
    val ready: Boolean? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val pcmFormat: String? = null
)

/**
 * Result of `POST /self-test`: the sidecar opened the mic, fed a few seconds through the
 * model, and reports the STAGE it got to. [ok] is true only when the wake phrase was
 * actually detected AND delivered — so the UI can be honest and never fake success.
 */
data class SelfTestResult(
    val stage: String,
    val ok: Boolean,
    val maxScore: Double? = null,
    val threshold: Double? = null,
    val message: String
)

/**
 * Result of `POST /calibrate`: the sidecar sampled the selected mic for a few seconds and
 * reports the RMS envelope + whether any real signal was detected (the direct dead-mic test).
 */
data class CalibrationResult(
    val device: String?,
    val frameCount: Long,
    val minRms: Double,
    val avgRms: Double,
    val maxRms: Double,
    val signalDetected: Boolean
)

/**
 * Transport seam for the local wake-word sidecar. The providers depend ONLY on
 * this interface, so unit tests inject a fake sidecar (no real network) and the
 * production [OkHttpWakeSidecarClient] is swapped in at runtime.
 *
 * Contract for [openEvents]: the implementation is responsible for SSE framing
 * (reading `text/event-stream`, stripping the `data: ` prefix). [onEvent] is
 * called with the raw JSON payload of each `data:` line; [onError] with any
 * stream failure. The returned [Closeable] cancels the stream.
 */
interface WakeSidecarClient {
    fun health(): SidecarHealth

    fun devices(): List<String>

    fun startEngine(request: StartEngineRequest): StartEngineResponse

    fun stopEngine(): Boolean

    /**
     * Pause detection on the sidecar (`POST /pause`) WITHOUT stopping the engine,
     * so it can be resumed cheaply during command recording / TTS. Returns whether
     * the sidecar acknowledged. Never throws.
     */
    fun pause(): Boolean

    /** Resume detection on the sidecar (`POST /resume`). Returns success. Never throws. */
    fun resume(): Boolean

    fun openEvents(onEvent: (String) -> Unit, onError: (Throwable) -> Unit): Closeable

    fun diagnostics(): SidecarDiagnosticsData?

    /**
     * Real staged self-test (`POST /self-test`): the sidecar opens the mic, feeds a few
     * seconds through the model, and reports which stage it reached. NEVER throws — returns
     * a failure [SelfTestResult] on any transport error. Default is "unsupported" so fakes
     * that predate self-test still satisfy the interface.
     */
    fun selfTest(): SelfTestResult =
        SelfTestResult(stage = "unsupported", ok = false, message = "Self-test not supported by this client.")

    /**
     * Calibrate the selected wake mic (`POST /calibrate` with `{seconds}`): the sidecar
     * samples RMS for [seconds] and reports min/avg/max + whether a real signal was seen.
     * NEVER throws — returns null on any transport error.
     */
    fun calibrate(seconds: Int): CalibrationResult? = null
}
