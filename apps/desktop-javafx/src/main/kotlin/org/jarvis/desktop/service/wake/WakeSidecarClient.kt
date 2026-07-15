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
    val paused: Boolean? = null
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
}
