package org.jarvis.desktop.service.wake

import org.jarvis.desktop.service.WakeWordDetector
import org.jarvis.desktop.service.WakeWordInitOutcome
import org.jarvis.desktop.service.WakeWordInitializer
import java.time.Instant

/**
 * Wraps the EXISTING Porcupine path ([WakeWordInitializer] + [WakeWordDetector])
 * behind the provider abstraction. It reuses the whole battle-tested
 * custom→built-in fallback tree unchanged.
 *
 * KEY-OPTIONAL: this provider is the ONLY one that needs a Picovoice access key,
 * and it must NEVER throw or block the chain when the key is absent/invalid. The
 * selector already gates it by [probeAvailable] so an invalid key can never stop
 * openWakeWord/Vosk from being reached; this class additionally returns a clean
 * "not started" result rather than throwing.
 *
 * @param keyValid cheap key check — VoiceTab passes
 *   `WakeWordDetector.validateAccessKey(...) == VALID`.
 * @param buildInitializer builds the production [WakeWordInitializer] (with the
 *   real `attempt` seam that constructs+starts a [WakeWordDetector]). Its wired
 *   detector should route its keyword-index callback into [emitWake] so a
 *   Porcupine hit becomes a normalized [WakeEvent].
 * @param onWake optional legacy index sink, invoked in addition to the
 *   [WakeWordCallback] on each wake (kept for callers that still want the raw
 *   keyword index).
 * @param model wake model label reported in the [WakeEvent] (Porcupine built-in).
 */
class PorcupineProvider(
    private val keyValid: () -> Boolean,
    private val buildInitializer: () -> WakeWordInitializer,
    private val onWake: (Int) -> Unit = {},
    private val model: String = "jarvis",
    private val nowIso: () -> String = { Instant.now().toString() }
) : WakeWordProvider {

    override val providerId: String = "porcupine"
    override val type: WakeWordProviderType = WakeWordProviderType.PORCUPINE

    @Volatile private var callback: WakeWordCallback? = null
    @Volatile private var handle: Any? = null
    @Volatile private var device: String? = null
    @Volatile private var lastState: WakeProviderState = WakeProviderState.UNAVAILABLE
    @Volatile private var lastScore: Double? = null
    @Volatile private var lastAt: String? = null
    // Fallback pause flag for when the handle is not a real WakeWordDetector (tests);
    // the real paused state is read from the detector via getState() when present.
    @Volatile private var pausedFlag = false

    override fun probeAvailable(): Boolean = safeKeyValid()

    override fun start(config: WakeWordConfig, callback: WakeWordCallback): WakeWordStartResult {
        this.callback = callback
        this.device = config.device

        if (!safeKeyValid()) {
            lastState = WakeProviderState.UNAVAILABLE
            return WakeWordStartResult(
                started = false,
                providerId = providerId,
                status = WakeProviderState.UNAVAILABLE,
                reason = "porcupine_unavailable: missing/invalid key"
            )
        }

        val outcome = try {
            buildInitializer().initialize()
        } catch (e: Exception) {
            lastState = WakeProviderState.ERROR
            return WakeWordStartResult(false, providerId, WakeProviderState.ERROR, "porcupine_init_error: ${e.message}")
        }

        return when (outcome) {
            is WakeWordInitOutcome.Enabled -> {
                handle = outcome.handle
                lastState = WakeProviderState.READY
                WakeWordStartResult(true, providerId, WakeProviderState.READY, null)
            }
            is WakeWordInitOutcome.Disabled -> {
                handle = null
                lastState = WakeProviderState.UNAVAILABLE
                WakeWordStartResult(false, providerId, WakeProviderState.UNAVAILABLE, outcome.reason)
            }
        }
    }

    /**
     * Bridge invoked by the live [WakeWordDetector]'s keyword-index callback:
     * converts a Porcupine hit into a normalized [WakeEvent] and fires the
     * [WakeWordCallback] (plus the optional legacy [onWake] sink). VoiceTab wires
     * the detector it builds to call this. Porcupine has no per-hit score, so a
     * conventional [score] of 1.0 is reported.
     */
    fun emitWake(keywordIndex: Int, score: Double = 1.0) {
        val ts = nowIso()
        lastScore = score
        lastAt = ts
        callback?.onWakeDetected(WakeEvent(providerId, model, score, device, ts))
        try {
            onWake(keywordIndex)
        } catch (_: Exception) {
            // Legacy sink must never break the wake path.
        }
    }

    override fun pause() {
        // No active detector → nothing to pause (no-op, never throws).
        val current = handle ?: return
        pausedFlag = true
        (current as? WakeWordDetector)?.let {
            try {
                it.pause()
            } catch (_: Exception) {
                // Best-effort; never throw from the wake path.
            }
        }
    }

    override fun resume() {
        val current = handle ?: return
        pausedFlag = false
        (current as? WakeWordDetector)?.let {
            try {
                it.resume()
            } catch (_: Exception) {
                // Best-effort; never throw from the wake path.
            }
        }
    }

    override fun stop() {
        (handle as? WakeWordDetector)?.let {
            try {
                it.stop()
            } catch (_: Exception) {
                // Best-effort stop; never throw.
            }
        }
        handle = null
        pausedFlag = false
        lastState = WakeProviderState.UNAVAILABLE
    }

    override fun status(): WakeWordStatus =
        if (isPausedNow()) WakeWordStatus(lastState, "$providerId paused") else WakeWordStatus(lastState, messageFor(lastState))

    override fun diagnostics(): WakeProviderDiagnostics = WakeProviderDiagnostics(
        providerId = providerId,
        installed = safeKeyValid(),
        reachable = null,
        models = listOf(model),
        listening = handle != null,
        lastWakeScore = lastScore,
        lastWakeDetectedAt = lastAt,
        lastError = null,
        paused = isPausedNow(),
        extra = mapOf("engine" to "porcupine", "keyValid" to safeKeyValid().toString())
    )

    /** True paused state from the live detector when present, else the fallback flag. */
    private fun isPausedNow(): Boolean {
        val detector = handle as? WakeWordDetector ?: return pausedFlag
        return detector.getState() == WakeWordDetector.ListeningState.PAUSED
    }

    private fun safeKeyValid(): Boolean = try {
        keyValid()
    } catch (_: Exception) {
        false
    }

    private fun messageFor(state: WakeProviderState): String = when (state) {
        WakeProviderState.READY, WakeProviderState.FALLBACK -> "Always Listening active using Porcupine."
        WakeProviderState.UNAVAILABLE -> "Porcupine wake word unavailable (access key missing/invalid)."
        WakeProviderState.ERROR -> "Porcupine failed to initialize."
    }
}
