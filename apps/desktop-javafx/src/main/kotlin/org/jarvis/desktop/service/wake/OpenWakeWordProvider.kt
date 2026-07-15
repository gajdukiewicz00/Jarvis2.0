package org.jarvis.desktop.service.wake

/**
 * PRIMARY provider: the local Python openWakeWord sidecar. Needs NO Picovoice
 * account / access key. All transport goes through the injected
 * [WakeSidecarClient] seam (tests inject a fake; production uses
 * [OkHttpWakeSidecarClient]).
 *
 * [autostart] is used by [probeAvailable]/start to spawn the sidecar
 * (`scripts/run-wakeword-openwakeword.sh`) when health is down, then poll up to
 * ~12s for it to come up. Default no-op returning false (tests / no sidecar).
 */
class OpenWakeWordProvider(
    http: WakeSidecarClient,
    autostart: () -> Boolean = { false },
    nowMs: () -> Long = { System.currentTimeMillis() },
    sleepMs: (Long) -> Unit = { Thread.sleep(it) }
) : SidecarWakeProvider(
    providerId = "openwakeword",
    type = WakeWordProviderType.OPENWAKEWORD,
    engineName = "openwakeword",
    http = http,
    autostart = autostart,
    nowMs = nowMs,
    sleepMs = sleepMs
) {
    override fun readyMessage(): String = "openWakeWord wake word engine active."
    override fun readyState(): WakeProviderState = WakeProviderState.READY
}
