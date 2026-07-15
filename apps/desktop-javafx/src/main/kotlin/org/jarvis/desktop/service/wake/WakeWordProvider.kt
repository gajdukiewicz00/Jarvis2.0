package org.jarvis.desktop.service.wake

/**
 * Provider-based wake-word abstraction.
 *
 * The goal is that Always-Listening works WITHOUT any Picovoice account /
 * PORCUPINE_ACCESS_KEY: a local Python openWakeWord sidecar is the primary
 * provider, a local Vosk phrase spotter is the secondary fallback, the existing
 * Porcupine path is used ONLY when a valid key happens to be present, and a
 * manual-only provider is the last-resort so Always-Listening never leaves the
 * app with "nothing" (Manual Talk always keeps working).
 *
 * Every provider is a small class implementing [WakeWordProvider]; the AUTO
 * selection chain lives in [WakeWordProviderSelector]; the wake→session hand-off
 * is guarded by [WakeEventGate]. None of these touch JavaFX, so they are all
 * unit-testable with fakes (no real sidecar / network / Porcupine).
 */
enum class WakeWordProviderType {
    /** Try providers in preference order (openWakeWord → Vosk → Porcupine → manual). */
    AUTO,

    /** Local Python openWakeWord sidecar (primary; no account needed). */
    OPENWAKEWORD,

    /** Local Vosk phrase spotter fallback (secondary; less accurate). */
    VOSK_PHRASE_SPOTTER,

    /** Existing Porcupine engine (only usable with a valid access key). */
    PORCUPINE,

    /** No wake detection — Manual Talk only. */
    MANUAL_ONLY
}

/** Coarse readiness of a wake-word provider. */
enum class WakeProviderState {
    /** Provider started and is actively listening. */
    READY,

    /** A less-preferred provider is active (e.g. Vosk, or manual-only). */
    FALLBACK,

    /** Provider is not available right now (sidecar down, key missing, ...). */
    UNAVAILABLE,

    /** Provider errored while starting or streaming. */
    ERROR
}

/**
 * Immutable configuration for wake-word selection.
 *
 * @param type which provider to use; [WakeWordProviderType.AUTO] runs the chain.
 * @param sidecarUrl base URL of the local openWakeWord/Vosk sidecar.
 * @param model wake-word model name the sidecar should load (e.g. "hey_jarvis").
 * @param threshold detection score threshold passed to the sidecar (0.0–1.0).
 * @param device capture device hint ("auto" lets the sidecar choose).
 */
data class WakeWordConfig(
    val type: WakeWordProviderType = WakeWordProviderType.AUTO,
    val sidecarUrl: String = "http://127.0.0.1:18095",
    val model: String = "hey_jarvis",
    val threshold: Double = 0.5,
    val device: String = "auto"
)

/**
 * A single wake detection, normalized across every provider so the UI/session
 * layer never needs to know which engine fired it.
 */
data class WakeEvent(
    val provider: String,
    val model: String,
    val score: Double,
    val device: String?,
    val timestampIso: String
)

/** Sink for wake events. A SAM-friendly interface so VoiceTab can pass a lambda. */
fun interface WakeWordCallback {
    fun onWakeDetected(event: WakeEvent)
}

/** Human-readable status snapshot for a provider (for the UI status line). */
data class WakeWordStatus(val state: WakeProviderState, val message: String)

/**
 * Result of [WakeWordProvider.start]: whether detection actually started, which
 * provider, its coarse state, and — when it did not start — a short machine
 * reason (e.g. "porcupine_unavailable: missing/invalid key", "vosk_not_installed",
 * "sidecar_unreachable: ...").
 */
data class WakeWordStartResult(
    val started: Boolean,
    val providerId: String,
    val status: WakeProviderState,
    val reason: String?
)

/**
 * A single wake-word provider. Implementations MUST be key-optional and never
 * throw from any method when a dependency (key / sidecar / model) is absent —
 * they return a "not started" result or an UNAVAILABLE status instead, so the
 * selector can fall through to the next provider without a crash.
 */
interface WakeWordProvider {
    /** Stable id used in diagnostics + attempt records (e.g. "openwakeword"). */
    val providerId: String

    /** The provider type this instance implements. */
    val type: WakeWordProviderType

    /**
     * Cheap availability probe that must NOT open a microphone or block for long.
     * The selector uses this to SKIP a provider it should not even attempt to
     * start (notably: Porcupine when the access key is missing/invalid).
     */
    fun probeAvailable(): Boolean

    /** Start detection, routing wake events to [callback]. Never throws. */
    fun start(config: WakeWordConfig, callback: WakeWordCallback): WakeWordStartResult

    /**
     * Pause detection WITHOUT tearing down the provider, so it can be resumed
     * cheaply after a command finishes recording / TTS finishes speaking. Idempotent
     * (calling it while already paused is a no-op) and never throws. Providers with
     * nothing to pause (e.g. manual-only) treat this as a no-op.
     */
    fun pause()

    /** Resume detection after a [pause]. Idempotent; never throws. */
    fun resume()

    /** Stop detection and release resources. Idempotent; never throws. */
    fun stop()

    /** Current status for the UI. Never throws. */
    fun status(): WakeWordStatus

    /** Diagnostics snapshot for the UI / logs. Never throws. */
    fun diagnostics(): WakeProviderDiagnostics
}
