package org.jarvis.desktop.config

import java.util.Locale

data class ResolvedDesktopConfig(
    val apiGatewayBaseUrl: String,
    val apiBaseUrl: String,
    val voiceWebSocketUrl: String,
    val pcControlWebSocketUrl: String,
    val locale: Locale,
    val voiceLanguage: String,
    val apiGatewaySource: ConfigSource,
    val apiGatewayReason: String,
    val usesManualEndpointOverride: Boolean
)

enum class ConfigSource(val description: String) {
    MANUAL_PERSISTED_SETTINGS("manual persisted settings"),
    ACTIVE_LOCAL_RUNTIME("active local runtime"),
    ENV_JARVIS_API_BASE_URL("environment JARVIS_API_BASE_URL"),
    ENV_API_URL("environment API_URL"),
    LEGACY_PERSISTED_SETTINGS("legacy persisted settings"),
    DEFAULT_LOCAL("local runtime default"),
    DEFAULT_INGRESS("ingress default")
}
