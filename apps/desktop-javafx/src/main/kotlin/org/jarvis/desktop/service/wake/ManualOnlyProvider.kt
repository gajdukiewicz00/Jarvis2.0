package org.jarvis.desktop.service.wake

/**
 * LAST-RESORT provider so the AUTO chain never leaves Always-Listening with
 * "nothing": it does no wake detection at all, always starts successfully, and
 * simply signals that only Manual Talk is available. This guarantees the
 * selector always returns a started provider even with no sidecar and no key.
 */
class ManualOnlyProvider : WakeWordProvider {

    override val providerId: String = "manual"
    override val type: WakeWordProviderType = WakeWordProviderType.MANUAL_ONLY

    override fun probeAvailable(): Boolean = true

    override fun start(config: WakeWordConfig, callback: WakeWordCallback): WakeWordStartResult =
        WakeWordStartResult(
            started = true,
            providerId = providerId,
            status = WakeProviderState.READY,
            reason = null
        )

    override fun pause() {
        // No-op: manual-only does no detection, so there is nothing to pause.
    }

    override fun resume() {
        // No-op: nothing to resume.
    }

    override fun stop() {
        // No-op: nothing to release.
    }

    override fun status(): WakeWordStatus =
        WakeWordStatus(WakeProviderState.READY, "Manual Talk only — wake word disabled.")

    override fun diagnostics(): WakeProviderDiagnostics = WakeProviderDiagnostics(
        providerId = providerId,
        installed = true,
        reachable = null,
        models = emptyList(),
        listening = false,
        lastWakeScore = null,
        lastWakeDetectedAt = null,
        lastError = null,
        extra = mapOf("mode" to "manual")
    )
}
