package org.jarvis.desktop.service.wake

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.time.Instant

/**
 * Shared logic for the two sidecar-backed providers ([OpenWakeWordProvider] and
 * [VoskPhraseSpotterProvider]). Both talk to the SAME kind of local sidecar via
 * the injected [WakeSidecarClient] seam and differ only in the engine name and
 * the status wording — so the health-ensure / start / SSE-parse / stop flow
 * lives here once (DRY).
 *
 * KEY-OPTIONAL + never-throw: every public method swallows transport errors and
 * reports them through the return value / status, so the selector can always
 * fall through to the next provider.
 *
 * Determinism: the health poll uses injected [nowMs] + [sleepMs] so tests drive
 * a fake clock instead of really sleeping.
 */
abstract class SidecarWakeProvider(
    override val providerId: String,
    override val type: WakeWordProviderType,
    protected val engineName: String,
    protected val http: WakeSidecarClient,
    /** Spawn the sidecar when health is down; default no-op (tests). Returns spawned?. */
    protected val autostart: () -> Boolean = { false },
    protected val nowMs: () -> Long = { System.currentTimeMillis() },
    protected val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
    private val nowIso: () -> String = { Instant.now().toString() }
) : WakeWordProvider {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var callback: WakeWordCallback? = null
    @Volatile private var activeConfig: WakeWordConfig? = null
    @Volatile private var eventStream: Closeable? = null
    @Volatile private var listening = false
    @Volatile private var paused = false
    @Volatile private var lastState: WakeProviderState = WakeProviderState.UNAVAILABLE
    @Volatile private var lastReachable: Boolean? = null
    @Volatile private var lastError: String? = null
    @Volatile private var lastWakeScore: Double? = null
    @Volatile private var lastWakeAt: String? = null

    /** Status wording for a READY/FALLBACK provider; overridden by Vosk. */
    protected open fun readyMessage(): String = "$providerId wake word engine active."

    /** READY for the primary engine; Vosk overrides to FALLBACK. */
    protected open fun readyState(): WakeProviderState = WakeProviderState.READY

    override fun probeAvailable(): Boolean {
        if (safeHealthUp()) return true
        if (!safeAutostart()) return false
        return pollHealthUp()
    }

    override fun start(config: WakeWordConfig, callback: WakeWordCallback): WakeWordStartResult {
        this.callback = callback
        this.activeConfig = config

        if (!ensureHealthy()) {
            lastState = WakeProviderState.UNAVAILABLE
            val reason = "sidecar_unreachable: $engineName sidecar at ${config.sidecarUrl} not reachable"
            lastError = reason
            return WakeWordStartResult(false, providerId, WakeProviderState.UNAVAILABLE, reason)
        }

        val response = try {
            http.startEngine(
                StartEngineRequest(
                    device = config.device,
                    model = config.model,
                    threshold = config.threshold,
                    engine = engineName
                )
            )
        } catch (e: Exception) {
            lastState = WakeProviderState.ERROR
            lastError = e.message
            return WakeWordStartResult(false, providerId, WakeProviderState.ERROR, "start_error: ${e.message}")
        }

        if (!response.started) {
            val state = if (response.statusCode == 503) WakeProviderState.UNAVAILABLE else WakeProviderState.ERROR
            val reason = response.error ?: "sidecar_start_failed_${response.statusCode}"
            lastState = state
            lastError = reason
            return WakeWordStartResult(false, providerId, state, reason)
        }

        eventStream = try {
            http.openEvents(onEvent = { onSseData(it) }, onError = { onSseError(it) })
        } catch (e: Exception) {
            logger.warn("wake.{}.events_open_failed: {}", providerId, e.message)
            lastError = e.message
            null
        }

        listening = true
        paused = false
        lastState = readyState()
        return WakeWordStartResult(true, providerId, readyState(), null)
    }

    override fun pause() {
        if (paused) return // idempotent: already paused → no redundant POST
        // Honest reconciliation: only report paused when the sidecar CONFIRMS it. If the
        // POST /pause fails (or throws), stay paused=false and surface the reason — never
        // claim we paused a sidecar that is still listening.
        val ok = safeCall("pause") { http.pause() }
        paused = ok
        if (!ok) {
            lastError = "pause_failed: $providerId sidecar did not confirm pause"
            logger.warn("wake.{}.pause_unconfirmed — provider stays not-paused", providerId)
        }
    }

    override fun resume() {
        if (!paused) return // idempotent: not paused → nothing to resume
        // Honest reconciliation with ONE retry: a single transient hiccup shouldn't strand us
        // paused. If resume is still unconfirmed after the retry, STAY paused (paused=true) and
        // surface the reason — never claim we resumed a sidecar that is still paused.
        var ok = safeCall("resume") { http.resume() }
        if (!ok) ok = safeCall("resume") { http.resume() }
        paused = !ok
        if (!ok) {
            lastError = "resume_failed: $providerId sidecar did not confirm resume"
            logger.warn("wake.{}.resume_unconfirmed — provider stays paused", providerId)
        }
    }

    /** Run a sidecar control POST, mapping any thrown transport error to `false` (never throws). */
    private fun safeCall(op: String, call: () -> Boolean): Boolean = try {
        call()
    } catch (e: Exception) {
        logger.debug("wake.{}.{} error: {}", providerId, op, e.message)
        false
    }

    override fun stop() {
        listening = false
        paused = false
        try {
            http.stopEngine()
        } catch (e: Exception) {
            logger.debug("wake.{}.stop_engine error: {}", providerId, e.message)
        }
        try {
            eventStream?.close()
        } catch (e: Exception) {
            logger.debug("wake.{}.stop_stream error: {}", providerId, e.message)
        }
        eventStream = null
        if (lastState != WakeProviderState.ERROR) lastState = WakeProviderState.UNAVAILABLE
    }

    override fun status(): WakeWordStatus =
        if (paused) WakeWordStatus(lastState, "$providerId paused") else WakeWordStatus(lastState, messageFor(lastState))

    override fun diagnostics(): WakeProviderDiagnostics {
        val data = try {
            http.diagnostics()
        } catch (e: Exception) {
            lastError = e.message
            null
        }
        return WakeProviderDiagnostics(
            providerId = providerId,
            installed = data?.installed,
            reachable = lastReachable,
            models = data?.models ?: emptyList(),
            listening = listening,
            lastWakeScore = data?.lastWakeScore ?: lastWakeScore,
            lastWakeDetectedAt = data?.lastWakeDetectedAt ?: lastWakeAt,
            lastError = data?.lastError ?: lastError,
            // Prefer the sidecar's own paused flag if it reports one; else the local flag.
            paused = data?.paused ?: paused,
            extra = mapOf(
                "engine" to engineName,
                "sidecarUrl" to (activeConfig?.sidecarUrl ?: "")
            )
        )
    }

    // ── internals ───────────────────────────────────────────────────────────

    private fun messageFor(state: WakeProviderState): String = when (state) {
        WakeProviderState.READY, WakeProviderState.FALLBACK -> readyMessage()
        WakeProviderState.UNAVAILABLE -> "$providerId sidecar unavailable."
        WakeProviderState.ERROR -> lastError ?: "$providerId error."
    }

    private fun ensureHealthy(): Boolean {
        if (safeHealthUp()) return true
        if (!safeAutostart()) return false
        return pollHealthUp()
    }

    private fun pollHealthUp(): Boolean {
        val deadline = nowMs() + HEALTH_POLL_TIMEOUT_MS
        while (nowMs() < deadline) {
            if (safeHealthUp()) return true
            sleepMs(HEALTH_POLL_INTERVAL_MS)
        }
        return safeHealthUp()
    }

    private fun safeHealthUp(): Boolean {
        val up = try {
            http.health().up
        } catch (e: Exception) {
            lastError = e.message
            false
        }
        lastReachable = up
        return up
    }

    private fun safeAutostart(): Boolean = try {
        autostart()
    } catch (e: Exception) {
        logger.debug("wake.{}.autostart error: {}", providerId, e.message)
        false
    }

    /** Parse one SSE `data:` JSON payload; fire the callback on WAKE_DETECTED. */
    private fun onSseData(payload: String) {
        val obj = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            logger.debug("wake.{}.sse_parse_ignored: {}", providerId, e.message)
            return
        }
        val kind = obj["event"]?.jsonPrimitive?.contentOrNull
            ?: obj["type"]?.jsonPrimitive?.contentOrNull
        if (kind != WAKE_DETECTED) return

        val model = obj["model"]?.jsonPrimitive?.contentOrNull ?: activeConfig?.model ?: "unknown"
        val score = obj["score"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val device = obj["device"]?.jsonPrimitive?.contentOrNull
        val ts = obj["timestamp"]?.jsonPrimitive?.contentOrNull
            ?: obj["timestampIso"]?.jsonPrimitive?.contentOrNull
            ?: nowIso()

        lastWakeScore = score
        lastWakeAt = ts
        callback?.onWakeDetected(WakeEvent(providerId, model, score, device, ts))
    }

    private fun onSseError(t: Throwable) {
        lastError = t.message
        lastState = WakeProviderState.ERROR
        logger.warn("wake.{}.sse_error: {}", providerId, t.message)
    }

    companion object {
        const val WAKE_DETECTED = "WAKE_DETECTED"
        const val HEALTH_POLL_TIMEOUT_MS = 12_000L
        const val HEALTH_POLL_INTERVAL_MS = 400L
    }
}
