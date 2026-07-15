package org.jarvis.desktop.service.wake

import org.slf4j.LoggerFactory

/**
 * Lifecycle owner for the active wake-word provider. There is exactly ONE active
 * provider at a time: [start] runs the [WakeWordProviderSelector], stores the
 * winning provider as [active], and — if a provider was already active — stops it
 * FIRST so two providers never stream at once (the leaked-capture bug the pause
 * work is guarding against). The JavaFX layer talks to THIS, never to raw
 * providers, so the "one active instance" and "resume-without-teardown" invariants
 * live in one place.
 *
 * Pause/resume are delegated to the active provider WITHOUT tearing it down, so a
 * command recording / TTS turn can hold detection off and restore it cheaply.
 *
 * Thread-safety: [start], [stop], [pause] and [resume] are synchronized on this
 * instance so they cannot interleave. Each [start] bumps a generation token and
 * wraps the caller's callback so that a late wake event from a provider that has
 * since been replaced/stopped is DROPPED (a stale provider can never start a
 * session against the new one).
 *
 * This class USES [WakeWordProviderSelector]; it does not replace it. Read paths
 * ([status], [diagnostics], [activeProviderId], ...) are lock-free over @Volatile
 * state so the UI can poll them without blocking a pause/resume.
 */
class WakeWordProviderManager(
    private val config: WakeWordConfig,
    private val selector: WakeWordProviderSelector
) {
    private val logger = LoggerFactory.getLogger(WakeWordProviderManager::class.java)

    @Volatile private var active: WakeWordProvider? = null
    @Volatile private var lastSelection: SelectionResult? = null
    @Volatile private var paused = false

    /** Bumped on every start/stop; only mutated/read under the instance lock. */
    private var generation = 0L

    /**
     * Select + start a provider, storing it as the single [active] one. Any
     * currently-active provider is stopped first (no double-active). The selector
     * ALWAYS returns something startable (manual last-resort), so this never leaves
     * the app with nothing.
     */
    @Synchronized
    fun start(callback: WakeWordCallback): SelectionResult {
        stopActiveLocked() // guarantees no double-active + invalidates old callbacks
        val myGeneration = generation
        val guarded = WakeWordCallback { event ->
            // Drop events from a provider that has since been replaced/stopped.
            val fresh = synchronized(this) { generation == myGeneration && active != null }
            if (fresh) {
                callback.onWakeDetected(event)
            } else {
                logger.debug("wake.manager.stale_event_dropped provider={} gen={}", event.provider, myGeneration)
            }
        }
        val result = selector.select(guarded)
        active = result.selected
        lastSelection = result
        paused = false
        logger.info("wake.manager.started provider={} type={}", result.selected?.providerId, result.selectedType)
        return result
    }

    /** Pause detection on the active provider without tearing it down. No-op if none. */
    @Synchronized
    fun pause() {
        val current = active ?: return
        current.pause()
        paused = true
    }

    /** Resume detection on the active provider after a [pause]. No-op if none. */
    @Synchronized
    fun resume() {
        val current = active ?: return
        current.resume()
        paused = false
    }

    /** Stop the active provider and clear it. Idempotent. */
    @Synchronized
    fun stop() {
        stopActiveLocked()
    }

    /** True while the active provider is paused. False when nothing is active. */
    fun isPaused(): Boolean = paused

    /** Id of the active provider (e.g. "openwakeword"), or null when none is active. */
    fun activeProviderId(): String? = active?.providerId

    /** Type of the active provider, or null when none is active. */
    fun activeType(): WakeWordProviderType? = active?.type

    /**
     * Status for the UI status line. Prefer the last SELECTION outcome when present: a
     * fallback/manual last-resort provider reports its own bare READY, which would hide the
     * fact that it is a FALLBACK — the selection result carries the honest state/message.
     * Fall back to the live provider status (started but never selection-recorded), then to
     * a "not started" default.
     */
    fun status(): WakeWordStatus =
        lastSelection?.let { WakeWordStatus(it.status, it.message) }
            ?: active?.status()
            ?: WakeWordStatus(WakeProviderState.UNAVAILABLE, "Wake word not started.")

    /** Aggregate diagnostics for EVERY configured provider (delegates to the selector). */
    fun diagnostics(): List<WakeProviderDiagnostics> = selector.providerDiagnostics()

    /** The most recent [start] selection outcome, or null before the first start. */
    fun lastSelection(): SelectionResult? = lastSelection

    /**
     * Stop the current active provider (if any), clear it, and bump the generation
     * so any in-flight/late callbacks bound to the old generation are dropped.
     * Caller MUST hold the instance lock.
     */
    private fun stopActiveLocked() {
        active?.let { provider ->
            try {
                provider.stop()
            } catch (e: Exception) {
                logger.debug("wake.manager.stop error for {}: {}", provider.providerId, e.message)
            }
        }
        active = null
        paused = false
        generation += 1
    }
}
