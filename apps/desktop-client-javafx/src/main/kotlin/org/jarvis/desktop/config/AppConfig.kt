package org.jarvis.desktop.config

import java.util.Locale

/**
 * Centralized application configuration for the desktop client.
 *
 * Persisted desktop settings take precedence over launcher defaults so a
 * developer can retarget the client without rewriting startup scripts.
 */
object AppConfig {
    private const val DEFAULT_API_GATEWAY = "https://api.jarvis.local"
    private const val DEFAULT_VOICE_DOMAIN = "voice.jarvis.local"

    private val settingsStore: DesktopSettingsStore = PreferencesDesktopSettingsStore()

    val apiGatewayBaseUrl: String
        get() = resolveApiGatewayBaseUrl(System.getenv(), settingsStore.load())

    val apiBaseUrl: String
        get() = "$apiGatewayBaseUrl/api/v1"

    val locale: Locale
        get() = resolveLocale(System.getenv(), settingsStore.load())

    val voiceLanguage: String
        get() = resolveVoiceLanguage(System.getenv(), locale)

    val pcControlWebSocketUrl: String
        get() = resolvePcControlWebSocketUrl(System.getenv(), settingsStore.load())

    val voiceWebSocketUrl: String
        get() = resolveVoiceWebSocketUrl(System.getenv(), settingsStore.load())

    fun saveSettings(apiGatewayBaseUrl: String, locale: Locale) {
        settingsStore.save(
            DesktopSettings(
                apiGatewayBaseUrl = normalizeBaseUrl(apiGatewayBaseUrl),
                localeTag = normalizeLocaleTag(locale.toLanguageTag())
            )
        )
    }

    internal fun resolveApiGatewayBaseUrl(environment: Map<String, String>, settings: DesktopSettings): String {
        val persistedBaseUrl = normalizeBaseUrl(settings.apiGatewayBaseUrl)
        val baseUrl = persistedBaseUrl
            ?: normalizeBaseUrl(environment["JARVIS_API_BASE_URL"])
            ?: normalizeBaseUrl(environment["API_URL"])
            ?: DEFAULT_API_GATEWAY

        validateBaseUrl(baseUrl, environment, honorTlsEnv = persistedBaseUrl == null)
        return baseUrl
    }

    internal fun resolvePcControlWebSocketUrl(environment: Map<String, String>, settings: DesktopSettings): String {
        return normalizeWebSocketUrl(environment["JARVIS_PC_WS_URL"])
            ?: "${wsBase(resolveApiGatewayBaseUrl(environment, settings))}/ws/pc-control"
    }

    internal fun resolveVoiceWebSocketUrl(environment: Map<String, String>, settings: DesktopSettings): String {
        normalizeWebSocketUrl(environment["JARVIS_VOICE_WS_URL"])?.let { return it }

        val baseUrl = resolveApiGatewayBaseUrl(environment, settings)
        val explicitVoiceDomain = environment["JARVIS_VOICE_DOMAIN"]?.trim()?.takeIf { it.isNotEmpty() }
        val useTls = baseUrl.startsWith("https://") || baseUrl.contains("jarvis.local")

        if (explicitVoiceDomain != null) {
            val scheme = if (useTls) "wss" else "ws"
            return "$scheme://$explicitVoiceDomain/ws/voice"
        }

        return if (baseUrl.contains("api.jarvis.local")) {
            "wss://$DEFAULT_VOICE_DOMAIN/ws/voice"
        } else {
            "${wsBase(baseUrl)}/ws/voice"
        }
    }

    internal fun resolveVoiceLanguage(environment: Map<String, String>, locale: Locale): String {
        environment["JARVIS_VOICE_LANGUAGE"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return if (locale.language.equals("ru", ignoreCase = true)) "ru-RU" else "en-US"
    }

    private fun resolveLocale(environment: Map<String, String>, settings: DesktopSettings): Locale {
        val localeTag = normalizeLocaleTag(settings.localeTag)
            ?: normalizeLocaleTag(environment["JARVIS_LOCALE"])
            ?: Locale.getDefault().toLanguageTag()

        val locale = Locale.forLanguageTag(localeTag)
        return if (locale.language.isBlank() || locale.language == "und") Locale.getDefault() else locale
    }

    private fun wsBase(baseUrl: String): String {
        return when {
            baseUrl.startsWith("https://") -> baseUrl.replaceFirst("^https".toRegex(), "wss")
            baseUrl.startsWith("http://") -> baseUrl.replaceFirst("^http".toRegex(), "ws")
            else -> {
                if (baseUrl.contains("api.jarvis.local") || baseUrl.contains("voice.jarvis.local")) {
                    baseUrl.replaceFirst("^http".toRegex(), "wss")
                } else {
                    baseUrl.replaceFirst("^http".toRegex(), "ws")
                }
            }
        }
    }

    private fun normalizeBaseUrl(url: String?): String? {
        return url?.trim()?.removeSuffix("/api/v1")?.trimEnd('/')?.takeIf { it.isNotBlank() }
    }

    private fun normalizeLocaleTag(localeTag: String?): String? {
        return localeTag?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun normalizeWebSocketUrl(url: String?): String? {
        return url?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
    }

    private fun validateBaseUrl(
        baseUrl: String,
        environment: Map<String, String>,
        honorTlsEnv: Boolean
    ) {
        val useTlsEnv = if (honorTlsEnv) {
            environment["JARVIS_USE_TLS"]?.toBooleanStrictOrNull()
        } else {
            null
        }
        val inferredTls = baseUrl.startsWith("https://") || baseUrl.contains("jarvis.local")
        val useTls = useTlsEnv ?: inferredTls
        val hasJarvisDomain = baseUrl.contains("jarvis.local")

        if (useTls && !baseUrl.startsWith("https://")) {
            throw IllegalStateException(
                "JARVIS_USE_TLS=true but API URL is not HTTPS: $baseUrl\n" +
                    "TLS mode requires all external URLs to use https:// and wss://"
            )
        }

        if (!useTls && hasJarvisDomain) {
            throw IllegalStateException(
                "JARVIS_USE_TLS=false but API URL contains jarvis.local domain: $baseUrl\n" +
                    "jarvis.local domains require TLS. Set JARVIS_USE_TLS=true or use IP/port URL"
            )
        }
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
}
