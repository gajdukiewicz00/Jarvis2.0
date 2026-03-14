package org.jarvis.desktop.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Locale

class AppConfigTest {

    @Test
    @DisplayName("saved desktop settings override environment defaults for API and websocket URLs")
    fun savedDesktopSettingsOverrideEnvironmentDefaults() {
        val environment = mapOf(
            "JARVIS_API_BASE_URL" to "https://api.jarvis.local",
            "JARVIS_USE_TLS" to "false"
        )
        val settings = DesktopSettings(
            apiGatewayBaseUrl = "http://127.0.0.1:8080",
            localeTag = "ru-RU"
        )

        assertEquals("http://127.0.0.1:8080", AppConfig.resolveApiGatewayBaseUrl(environment, settings))
        assertEquals("ws://127.0.0.1:8080/ws/pc-control", AppConfig.resolvePcControlWebSocketUrl(environment, settings))
        assertEquals("ws://127.0.0.1:8080/ws/voice", AppConfig.resolveVoiceWebSocketUrl(environment, settings))
    }

    @Test
    @DisplayName("voice language follows supported locale mapping")
    fun voiceLanguageFollowsSupportedLocaleMapping() {
        assertEquals("ru-RU", AppConfig.resolveVoiceLanguage(emptyMap(), Locale("ru", "RU")))
        assertEquals("en-US", AppConfig.resolveVoiceLanguage(emptyMap(), Locale("pl", "PL")))
    }

    @Test
    @DisplayName("jarvis local domains still require TLS")
    fun jarvisLocalDomainsStillRequireTls() {
        val environment = mapOf("JARVIS_USE_TLS" to "false")
        val settings = DesktopSettings(apiGatewayBaseUrl = "http://api.jarvis.local")

        assertThrows(IllegalStateException::class.java) {
            AppConfig.resolveApiGatewayBaseUrl(environment, settings)
        }
    }
}
