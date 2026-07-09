package org.jarvis.desktop.config

import java.util.Locale

/**
 * Centralized application configuration for the desktop client.
 *
 * Active runtime summary endpoints win by default unless the user explicitly
 * pins a manual endpoint override in desktop settings.
 */
object AppConfig {
    private val settingsStore: DesktopSettingsStore = PreferencesDesktopSettingsStore()
    private val localRuntimeDetector = LocalRuntimeEndpointDetector()
    private val configService = DesktopConfigService(
        settingsStore = settingsStore,
        localRuntimeEndpointProvider = localRuntimeDetector::detectActive,
        runtimeSummaryFingerprintProvider = localRuntimeDetector::summaryFingerprint,
        endpointHealthProbe = localRuntimeDetector::isReachable
    )

    fun current(): ResolvedDesktopConfig = configService.current()

    fun reload(): ResolvedDesktopConfig = configService.reload()

    val apiGatewayBaseUrl: String
        get() = current().apiGatewayBaseUrl

    val apiBaseUrl: String
        get() = current().apiBaseUrl

    val locale: Locale
        get() = current().locale

    val voiceLanguage: String
        get() = current().voiceLanguage

    val pcControlWebSocketUrl: String
        get() = current().pcControlWebSocketUrl

    val voiceWebSocketUrl: String
        get() = current().voiceWebSocketUrl

    val apiGatewaySource: ConfigSource
        get() = current().apiGatewaySource

    val apiGatewayReason: String
        get() = current().apiGatewayReason

    val usesManualEndpointOverride: Boolean
        get() = current().usesManualEndpointOverride

    fun saveSettings(
        apiGatewayBaseUrl: String,
        locale: Locale,
        manualEndpointOverride: Boolean = current().usesManualEndpointOverride
    ): ResolvedDesktopConfig {
        return configService.saveSettings(apiGatewayBaseUrl, locale, manualEndpointOverride)
    }

    fun addListener(listener: (ResolvedDesktopConfig) -> Unit) {
        configService.addListener(listener)
    }

    fun removeListener(listener: (ResolvedDesktopConfig) -> Unit) {
        configService.removeListener(listener)
    }

    internal fun resolve(environment: Map<String, String>, settings: DesktopSettings): ResolvedDesktopConfig {
        return DesktopConfigResolver.resolve(environment, settings)
    }

    internal fun resolve(
        environment: Map<String, String>,
        settings: DesktopSettings,
        localRuntimeEndpoint: LocalRuntimeEndpointSnapshot?,
        manualEndpointReachable: Boolean? = null
    ): ResolvedDesktopConfig {
        return DesktopConfigResolver.resolve(environment, settings, localRuntimeEndpoint, manualEndpointReachable)
    }

    internal fun resolveApiGatewayBaseUrl(environment: Map<String, String>, settings: DesktopSettings): String {
        return resolve(environment, settings).apiGatewayBaseUrl
    }

    internal fun resolvePcControlWebSocketUrl(environment: Map<String, String>, settings: DesktopSettings): String {
        return resolve(environment, settings).pcControlWebSocketUrl
    }

    internal fun resolveVoiceWebSocketUrl(environment: Map<String, String>, settings: DesktopSettings): String {
        return resolve(environment, settings).voiceWebSocketUrl
    }

    internal fun resolveVoiceLanguage(environment: Map<String, String>, settings: DesktopSettings, locale: Locale): String {
        return VoiceRecognitionLanguage.resolve(environment, settings, locale)
    }

    internal fun normalizeBaseUrl(url: String?): String? = DesktopConfigResolver.normalizeBaseUrl(url)

    internal fun normalizeLocaleTag(localeTag: String?): String? = DesktopConfigResolver.normalizeLocaleTag(localeTag)

    internal fun normalizePersistedSettings(
        apiGatewayBaseUrl: String,
        locale: Locale,
        manualEndpointOverride: Boolean
    ): DesktopSettings {
        return DesktopConfigResolver.normalizePersistedSettings(apiGatewayBaseUrl, locale, manualEndpointOverride)
    }
}

/**
 * Voice session configuration with sensible defaults.
 * All values can be overridden via environment variables.
 */
object VoiceConfig {
    val cooldownMs: Long by lazy {
        System.getenv("JARVIS_VOICE_COOLDOWN_MS")?.toLongOrNull() ?: 1500L
    }

    val listenTimeoutMs: Long by lazy {
        System.getenv("JARVIS_VOICE_LISTEN_TIMEOUT_MS")?.toLongOrNull() ?: 10000L
    }

    val processingTimeoutMs: Long by lazy {
        System.getenv("JARVIS_VOICE_PROCESSING_TIMEOUT_MS")?.toLongOrNull() ?: 15000L
    }

    val minTranscriptLength: Int by lazy {
        System.getenv("JARVIS_VOICE_MIN_TRANSCRIPT_LENGTH")?.toIntOrNull() ?: 2
    }

    val noiseFilteringEnabled: Boolean by lazy {
        System.getenv("JARVIS_VOICE_NOISE_FILTERING")?.toBoolean() ?: true
    }

    val noiseWords: Set<String> by lazy {
        val env = System.getenv("JARVIS_VOICE_NOISE_WORDS")
        if (env.isNullOrBlank()) {
            setOf("а", "э", "м", "эм", "хм", "ага", "угу", "ну", "так", "вот", "ой", "ах")
        } else {
            env.split(" ").filter { it.isNotBlank() }.toSet()
        }
    }

    val wakeWordDelayMs: Long by lazy {
        System.getenv("JARVIS_VOICE_WAKE_WORD_DELAY_MS")?.toLongOrNull() ?: 300L
    }

    /** Max time to wait in TTS_PLAYBACK (SPEAKING) before force-recovering — a hung audio
     *  device (line.drain blocking) must never freeze the session forever. */
    val maxSpeakingMs: Long by lazy {
        System.getenv("JARVIS_VOICE_MAX_SPEAKING_MS")?.toLongOrNull() ?: 30000L
    }

    /** How often the session watchdog checks for a stuck state. */
    val watchdogIntervalMs: Long by lazy {
        System.getenv("JARVIS_VOICE_WATCHDOG_INTERVAL_MS")?.toLongOrNull() ?: 3000L
    }

    /** Grace window after a RESPONSE frame to wait for TTS audio before treating it as
     *  text-only and recovering (instead of waiting out the full processing timeout). */
    val textOnlyGraceMs: Long by lazy {
        System.getenv("JARVIS_VOICE_TEXT_ONLY_GRACE_MS")?.toLongOrNull() ?: 2500L
    }

    /** Absolute upper bound for any single active (non-idle) state; the watchdog force-recovers
     *  past this even if a per-state timeout somehow didn't fire. */
    val maxActiveStateMs: Long by lazy {
        System.getenv("JARVIS_VOICE_MAX_ACTIVE_STATE_MS")?.toLongOrNull() ?: 45000L
    }
}
