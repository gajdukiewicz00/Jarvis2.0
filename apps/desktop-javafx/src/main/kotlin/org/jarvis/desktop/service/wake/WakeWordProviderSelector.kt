package org.jarvis.desktop.service.wake

import org.slf4j.LoggerFactory

/** One provider start attempt in the fallback chain, recorded for diagnostics. */
data class AttemptRecord(val providerId: String, val started: Boolean, val reason: String?)

/**
 * Outcome of [WakeWordProviderSelector.select].
 *
 * @param selected the provider that actually started (null only if even manual
 *   was not configured — degenerate).
 * @param selectedType its type.
 * @param status READY when the primary/requested provider won; FALLBACK when a
 *   less-preferred provider (Vosk, Porcupine-as-fallback, or manual) won.
 * @param fallbackChain every attempt in order, with per-attempt reason.
 * @param message the user-facing status line (section 9 wording).
 */
data class SelectionResult(
    val selected: WakeWordProvider?,
    val selectedType: WakeWordProviderType,
    val status: WakeProviderState,
    val fallbackChain: List<AttemptRecord>,
    val message: String
)

/**
 * The AUTO selection chain — the core of the abstraction.
 *
 * AUTO order: openWakeWord → Vosk phrase spotter → Porcupine → manual.
 * The first provider whose `start()` returns `started=true` wins. Porcupine is
 * gated by [WakeWordProvider.probeAvailable] (its key check), so an invalid /
 * missing Porcupine key can NEVER block reaching openWakeWord or Vosk — it is
 * SKIPPED (recorded, not started).
 *
 * When a specific (non-AUTO) type is configured, only that provider is tried; if
 * it fails to start, the selector still falls through to manual so it never
 * leaves the app with nothing.
 *
 * Pure/deterministic given the injected providers — unit-tested with fakes.
 */
class WakeWordProviderSelector(
    private val config: WakeWordConfig,
    private val providers: Map<WakeWordProviderType, WakeWordProvider>
) {
    private val logger = LoggerFactory.getLogger(WakeWordProviderSelector::class.java)

    fun select(callback: WakeWordCallback): SelectionResult =
        if (config.type == WakeWordProviderType.AUTO) selectAuto(callback) else selectExplicit(config.type, callback)

    /** Aggregate diagnostics for every configured provider (for the UI). */
    fun providerDiagnostics(): List<WakeProviderDiagnostics> =
        providers.values.map { provider ->
            try {
                provider.diagnostics()
            } catch (e: Exception) {
                WakeProviderDiagnostics(
                    providerId = provider.providerId,
                    installed = null,
                    reachable = null,
                    models = emptyList(),
                    listening = false,
                    lastWakeScore = null,
                    lastWakeDetectedAt = null,
                    lastError = e.message
                )
            }
        }

    private fun selectAuto(callback: WakeWordCallback): SelectionResult {
        val chain = mutableListOf<AttemptRecord>()
        for (type in AUTO_ORDER) {
            val provider = providers[type] ?: continue

            if (type == WakeWordProviderType.PORCUPINE && !safeProbe(provider)) {
                chain += AttemptRecord(provider.providerId, false, PORCUPINE_SKIP_REASON)
                logger.info("wake.selector.skip porcupine (key invalid/missing)")
                continue
            }

            val result = safeStart(provider, callback)
            chain += AttemptRecord(provider.providerId, result.started, result.reason)
            if (result.started) return success(provider, type, chain)
        }
        return degenerateManual(chain)
    }

    private fun selectExplicit(type: WakeWordProviderType, callback: WakeWordCallback): SelectionResult {
        val chain = mutableListOf<AttemptRecord>()
        val provider = providers[type]

        if (provider == null) {
            chain += AttemptRecord(type.name.lowercase(), false, "provider_not_configured")
        } else if (type == WakeWordProviderType.PORCUPINE && !safeProbe(provider)) {
            chain += AttemptRecord(provider.providerId, false, PORCUPINE_SKIP_REASON)
        } else {
            val result = safeStart(provider, callback)
            chain += AttemptRecord(provider.providerId, result.started, result.reason)
            if (result.started) return success(provider, type, chain)
        }

        // Requested provider failed / missing → fall through to manual, never nothing.
        return fallbackToManual(callback, chain)
    }

    private fun fallbackToManual(callback: WakeWordCallback, chain: MutableList<AttemptRecord>): SelectionResult {
        val manual = providers[WakeWordProviderType.MANUAL_ONLY] ?: return degenerateManual(chain)
        val result = safeStart(manual, callback)
        chain += AttemptRecord(manual.providerId, result.started, result.reason)
        return success(manual, WakeWordProviderType.MANUAL_ONLY, chain)
    }

    private fun success(
        provider: WakeWordProvider,
        type: WakeWordProviderType,
        chain: List<AttemptRecord>
    ): SelectionResult {
        val primary = if (config.type == WakeWordProviderType.AUTO) WakeWordProviderType.OPENWAKEWORD else config.type
        val status = if (type == primary) WakeProviderState.READY else WakeProviderState.FALLBACK
        return SelectionResult(provider, type, status, chain, messageFor(type))
    }

    /** Only reached if no manual provider was configured — still return something safe. */
    private fun degenerateManual(chain: List<AttemptRecord>): SelectionResult = SelectionResult(
        selected = null,
        selectedType = WakeWordProviderType.MANUAL_ONLY,
        status = WakeProviderState.FALLBACK,
        fallbackChain = chain,
        message = messageFor(WakeWordProviderType.MANUAL_ONLY)
    )

    private fun safeProbe(provider: WakeWordProvider): Boolean = try {
        provider.probeAvailable()
    } catch (e: Exception) {
        logger.debug("wake.selector.probe error for {}: {}", provider.providerId, e.message)
        false
    }

    private fun safeStart(provider: WakeWordProvider, callback: WakeWordCallback): WakeWordStartResult = try {
        provider.start(config, callback)
    } catch (e: Exception) {
        logger.warn("wake.selector.start threw for {}: {}", provider.providerId, e.message)
        WakeWordStartResult(false, provider.providerId, WakeProviderState.ERROR, "start_exception: ${e.message}")
    }

    companion object {
        val AUTO_ORDER: List<WakeWordProviderType> = listOf(
            WakeWordProviderType.OPENWAKEWORD,
            WakeWordProviderType.VOSK_PHRASE_SPOTTER,
            WakeWordProviderType.PORCUPINE,
            WakeWordProviderType.MANUAL_ONLY
        )

        const val PORCUPINE_SKIP_REASON = "skipped: porcupine key invalid/missing"

        fun messageFor(type: WakeWordProviderType): String = when (type) {
            WakeWordProviderType.OPENWAKEWORD -> "Always Listening active using openWakeWord."
            WakeWordProviderType.VOSK_PHRASE_SPOTTER -> "Always Listening active using Vosk phrase spotter fallback."
            WakeWordProviderType.PORCUPINE -> "Always Listening active using Porcupine."
            WakeWordProviderType.MANUAL_ONLY -> "Wake word unavailable. Manual Talk still works."
            WakeWordProviderType.AUTO -> "Selecting wake word provider..."
        }
    }
}
