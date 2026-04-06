package org.jarvis.desktop.config

import java.util.Locale

internal object DesktopConfigResolver {
    private const val DEFAULT_LOCAL_API_GATEWAY = "https://127.0.0.1:18080"
    private const val DEFAULT_REMOTE_API_GATEWAY = "https://api.jarvis.local"

    fun resolve(
        environment: Map<String, String>,
        settings: DesktopSettings,
        localRuntimeEndpoint: LocalRuntimeEndpointSnapshot? = null
    ): ResolvedDesktopConfig {
        val resolvedBaseUrl = resolveApiGateway(environment, settings, localRuntimeEndpoint)
        val locale = resolveLocale(environment, settings)

        validateBaseUrl(
            baseUrl = resolvedBaseUrl.value,
            environment = environment,
            honorTlsEnv = resolvedBaseUrl.source !in setOf(
                ConfigSource.MANUAL_PERSISTED_SETTINGS,
                ConfigSource.LEGACY_PERSISTED_SETTINGS,
                ConfigSource.ACTIVE_LOCAL_RUNTIME
            )
        )

        return ResolvedDesktopConfig(
            apiGatewayBaseUrl = resolvedBaseUrl.value,
            apiBaseUrl = "${resolvedBaseUrl.value}/api/v1",
            voiceWebSocketUrl = "${wsBase(resolvedBaseUrl.value)}/ws/voice",
            pcControlWebSocketUrl = "${wsBase(resolvedBaseUrl.value)}/ws/pc-control",
            locale = locale,
            voiceLanguage = resolveVoiceLanguage(environment, settings, locale),
            apiGatewaySource = resolvedBaseUrl.source,
            apiGatewayReason = resolvedBaseUrl.reason,
            usesManualEndpointOverride = resolvedBaseUrl.usesManualEndpointOverride
        )
    }

    fun normalizePersistedSettings(
        apiGatewayBaseUrl: String,
        locale: Locale,
        manualEndpointOverride: Boolean
    ): DesktopSettings {
        return DesktopSettings(
            apiGatewayBaseUrl = if (manualEndpointOverride) normalizeBaseUrl(apiGatewayBaseUrl) else null,
            localeTag = normalizeLocaleTag(locale.toLanguageTag()),
            endpointSelectionMode = if (manualEndpointOverride) EndpointSelectionMode.MANUAL else EndpointSelectionMode.AUTO
        )
    }

    fun normalizeBaseUrl(url: String?): String? {
        val trimmed = url?.trim()
            ?.removeSuffix("/api/v1")
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val withoutScheme = trimmed.substringAfter("://", trimmed)

        return when {
            trimmed.startsWith("http://", ignoreCase = true) -> "http://$withoutScheme"
            trimmed.startsWith("https://", ignoreCase = true) -> "https://$withoutScheme"
            trimmed.contains("jarvis.local", ignoreCase = true) -> "https://$trimmed"
            else -> "http://$trimmed"
        }
    }

    fun normalizeLocaleTag(localeTag: String?): String? {
        return localeTag?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun resolveApiGateway(
        environment: Map<String, String>,
        settings: DesktopSettings,
        localRuntimeEndpoint: LocalRuntimeEndpointSnapshot?
    ): ResolvedValue<String> {
        val persistedBaseUrl = normalizeBaseUrl(settings.apiGatewayBaseUrl)
        val selectionMode = settings.endpointSelectionMode

        if (selectionMode == EndpointSelectionMode.MANUAL && persistedBaseUrl != null) {
            val reason = buildString {
                append("Manual endpoint override is pinned in desktop settings")
                if (localRuntimeEndpoint != null) {
                    append("; active local runtime ${localRuntimeEndpoint.apiGatewayBaseUrl} was intentionally bypassed")
                }
            }
            return ResolvedValue(
                persistedBaseUrl,
                ConfigSource.MANUAL_PERSISTED_SETTINGS,
                reason,
                usesManualEndpointOverride = true
            )
        }

        if (localRuntimeEndpoint != null) {
            return ResolvedValue(
                localRuntimeEndpoint.apiGatewayBaseUrl,
                ConfigSource.ACTIVE_LOCAL_RUNTIME,
                "${localRuntimeEndpoint.reason}; no manual endpoint override is active"
            )
        }

        normalizeBaseUrl(environment["JARVIS_API_BASE_URL"])?.let {
            return ResolvedValue(
                it,
                ConfigSource.ENV_JARVIS_API_BASE_URL,
                "Using environment JARVIS_API_BASE_URL because no active local runtime override was detected"
            )
        }

        normalizeBaseUrl(environment["API_URL"])?.let {
            return ResolvedValue(
                it,
                ConfigSource.ENV_API_URL,
                "Using environment API_URL because no active local runtime override was detected"
            )
        }

        persistedBaseUrl?.let {
            return ResolvedValue(
                it,
                ConfigSource.LEGACY_PERSISTED_SETTINGS,
                "Using legacy persisted endpoint because no active local runtime or explicit environment endpoint was detected"
            )
        }

        return if (runtimeMode(environment) == "k8s") {
            ResolvedValue(
                DEFAULT_REMOTE_API_GATEWAY,
                ConfigSource.DEFAULT_INGRESS,
                "Falling back to ingress default because no runtime-specific or explicit endpoint source was available"
            )
        } else {
            ResolvedValue(
                DEFAULT_LOCAL_API_GATEWAY,
                ConfigSource.DEFAULT_LOCAL,
                "Falling back to local runtime default because no runtime-specific or explicit endpoint source was available"
            )
        }
    }

    private fun resolveLocale(environment: Map<String, String>, settings: DesktopSettings): Locale {
        val localeTag = normalizeLocaleTag(settings.localeTag)
            ?: normalizeLocaleTag(environment["JARVIS_LOCALE"])
            ?: Locale.getDefault().toLanguageTag()

        val locale = Locale.forLanguageTag(localeTag)
        return if (locale.language.isBlank() || locale.language == "und") Locale.getDefault() else locale
    }

    private fun resolveVoiceLanguage(
        environment: Map<String, String>,
        settings: DesktopSettings,
        locale: Locale
    ): String {
        return VoiceRecognitionLanguage.resolve(environment, settings, locale)
    }

    private fun wsBase(baseUrl: String): String {
        return when {
            baseUrl.startsWith("https://") -> baseUrl.replaceFirst("https://", "wss://")
            baseUrl.startsWith("http://") -> baseUrl.replaceFirst("http://", "ws://")
            else -> throw IllegalStateException("Unsupported API base URL: $baseUrl")
        }
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
        val inferredTls = baseUrl.startsWith("https://")
        val useTls = useTlsEnv ?: inferredTls
        val hasJarvisDomain = baseUrl.contains("jarvis.local", ignoreCase = true)

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

    private fun runtimeMode(environment: Map<String, String>): String? {
        val configuredMode = environment["JARVIS_RUNTIME_MODE"]?.trim()?.lowercase()
        if (configuredMode == "local" || configuredMode == "k8s") {
            return configuredMode
        }

        val explicitUrl = environment["JARVIS_API_BASE_URL"] ?: environment["API_URL"] ?: return null
        val normalizedUrl = explicitUrl.lowercase()
        return if (normalizedUrl.contains("127.0.0.1") || normalizedUrl.contains("localhost")) {
            "local"
        } else {
            "k8s"
        }
    }

    private data class ResolvedValue<T>(
        val value: T,
        val source: ConfigSource,
        val reason: String,
        val usesManualEndpointOverride: Boolean = false
    )
}
