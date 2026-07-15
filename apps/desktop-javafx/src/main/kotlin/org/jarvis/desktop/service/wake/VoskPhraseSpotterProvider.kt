package org.jarvis.desktop.service.wake

/**
 * SECONDARY fallback provider: a local Vosk phrase spotter running in the SAME
 * kind of sidecar, started with `engine:"vosk"`. Less accurate than openWakeWord
 * but needs no account either.
 *
 * If the sidecar reports Vosk is not installed (`POST /start` → 503 /
 * {"error":"vosk_not_installed"}), the base surfaces that verbatim as the start
 * reason ("vosk_not_installed"), so the selector moves on cleanly.
 */
class VoskPhraseSpotterProvider(
    http: WakeSidecarClient,
    autostart: () -> Boolean = { false },
    nowMs: () -> Long = { System.currentTimeMillis() },
    sleepMs: (Long) -> Unit = { Thread.sleep(it) }
) : SidecarWakeProvider(
    providerId = "vosk",
    type = WakeWordProviderType.VOSK_PHRASE_SPOTTER,
    engineName = "vosk",
    http = http,
    autostart = autostart,
    nowMs = nowMs,
    sleepMs = sleepMs
) {
    override fun readyMessage(): String =
        "Vosk phrase spotter fallback active — less accurate than openWakeWord."

    override fun readyState(): WakeProviderState = WakeProviderState.FALLBACK
}
