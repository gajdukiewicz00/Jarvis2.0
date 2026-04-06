package org.jarvis.desktop.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Locale

class AppConfigTest {

    @Test
    @DisplayName("manual endpoint override beats environment defaults for API and websocket URLs")
    fun manualEndpointOverrideBeatsEnvironmentDefaults() {
        val environment = mapOf(
            "JARVIS_API_BASE_URL" to "https://api.jarvis.local",
            "JARVIS_USE_TLS" to "false"
        )
        val settings = DesktopSettings(
            apiGatewayBaseUrl = "http://127.0.0.1:8080",
            localeTag = "ru-RU",
            endpointSelectionMode = EndpointSelectionMode.MANUAL
        )

        assertEquals("http://127.0.0.1:8080", AppConfig.resolveApiGatewayBaseUrl(environment, settings))
        assertEquals("ws://127.0.0.1:8080/ws/pc-control", AppConfig.resolvePcControlWebSocketUrl(environment, settings))
        assertEquals("ws://127.0.0.1:8080/ws/voice", AppConfig.resolveVoiceWebSocketUrl(environment, settings))
    }

    @Test
    @DisplayName("remote API base derives both websocket endpoints from the same host")
    fun remoteApiBaseDerivesBothWebSocketsFromGatewayHost() {
        val resolved = AppConfig.resolve(
            environment = mapOf("JARVIS_API_BASE_URL" to "https://api.jarvis.local"),
            settings = DesktopSettings()
        )

        assertEquals("https://api.jarvis.local", resolved.apiGatewayBaseUrl)
        assertEquals("https://api.jarvis.local/api/v1", resolved.apiBaseUrl)
        assertEquals("wss://api.jarvis.local/ws/voice", resolved.voiceWebSocketUrl)
        assertEquals("wss://api.jarvis.local/ws/pc-control", resolved.pcControlWebSocketUrl)
        assertEquals(ConfigSource.ENV_JARVIS_API_BASE_URL, resolved.apiGatewaySource)
    }

    @Test
    @DisplayName("local runtime mode uses localhost default when nothing else is configured")
    fun localRuntimeModeDefaultsToLocalGateway() {
        val resolved = AppConfig.resolve(
            environment = mapOf("JARVIS_RUNTIME_MODE" to "local"),
            settings = DesktopSettings()
        )

        assertEquals("https://127.0.0.1:18080", resolved.apiGatewayBaseUrl)
        assertEquals("wss://127.0.0.1:18080/ws/voice", resolved.voiceWebSocketUrl)
        assertEquals(ConfigSource.DEFAULT_LOCAL, resolved.apiGatewaySource)
    }

    @Test
    @DisplayName("desktop defaults to localhost when no endpoint is configured")
    fun zeroConfigDefaultsToLocalGateway() {
        val resolved = AppConfig.resolve(
            environment = emptyMap(),
            settings = DesktopSettings()
        )

        assertEquals("https://127.0.0.1:18080", resolved.apiGatewayBaseUrl)
        assertEquals(ConfigSource.DEFAULT_LOCAL, resolved.apiGatewaySource)
    }

    @Test
    @DisplayName("base URL normalization adds schemes for host-only inputs")
    fun baseUrlNormalizationAddsSchemes() {
        assertEquals("http://127.0.0.1:8080", AppConfig.normalizeBaseUrl("127.0.0.1:8080"))
        assertEquals("https://api.jarvis.local", AppConfig.normalizeBaseUrl("api.jarvis.local"))
    }

    @Test
    @DisplayName("explicit persisted Russian locale selects Russian STT")
    fun explicitPersistedRussianLocaleSelectsRussianStt() {
        assertEquals(
            "ru-RU",
            AppConfig.resolveVoiceLanguage(
                emptyMap(),
                DesktopSettings(localeTag = "ru-RU"),
                Locale.ENGLISH
            )
        )
    }

    @Test
    @DisplayName("explicit persisted English locale selects English STT")
    fun explicitPersistedEnglishLocaleSelectsEnglishStt() {
        assertEquals(
            "en-US",
            AppConfig.resolveVoiceLanguage(
                emptyMap(),
                DesktopSettings(localeTag = "en-US"),
                Locale("ru", "RU")
            )
        )
    }

    @Test
    @DisplayName("explicit environment Russian locale selects Russian STT")
    fun explicitEnvironmentRussianLocaleSelectsRussianStt() {
        assertEquals(
            "ru-RU",
            AppConfig.resolveVoiceLanguage(
                mapOf("JARVIS_LOCALE" to "ru-RU"),
                DesktopSettings(),
                Locale.ENGLISH
            )
        )
    }

    @Test
    @DisplayName("explicit environment English locale selects English STT")
    fun explicitEnvironmentEnglishLocaleSelectsEnglishStt() {
        assertEquals(
            "en-US",
            AppConfig.resolveVoiceLanguage(
                mapOf("JARVIS_LOCALE" to "en-US"),
                DesktopSettings(),
                Locale("ru", "RU")
            )
        )
    }

    @Test
    @DisplayName("voice language defaults to Russian when no explicit locale is configured")
    fun voiceLanguageDefaultsToRussianWhenNoExplicitLocaleIsConfigured() {
        assertEquals(
            "ru-RU",
            AppConfig.resolveVoiceLanguage(emptyMap(), DesktopSettings(), Locale("pl", "PL"))
        )
    }

    @Test
    @DisplayName("explicit voice language override still wins")
    fun explicitVoiceLanguageOverrideStillWins() {
        assertEquals(
            "en-US",
            AppConfig.resolveVoiceLanguage(
                mapOf("JARVIS_VOICE_LANGUAGE" to "en"),
                DesktopSettings(),
                Locale("ru", "RU")
            )
        )
    }

    @Test
    @DisplayName("jarvis local domains still require TLS")
    fun jarvisLocalDomainsStillRequireTls() {
        val environment = mapOf("JARVIS_USE_TLS" to "false")
        val settings = DesktopSettings(
            apiGatewayBaseUrl = "http://api.jarvis.local",
            endpointSelectionMode = EndpointSelectionMode.MANUAL
        )

        assertThrows(IllegalStateException::class.java) {
            AppConfig.resolveApiGatewayBaseUrl(environment, settings)
        }
    }

    @Test
    @DisplayName("active local runtime wins over legacy persisted endpoint")
    fun activeLocalRuntimeWinsOverLegacyPersistedEndpoint() {
        val resolved = AppConfig.resolve(
            environment = mapOf("JARVIS_API_BASE_URL" to "https://api.jarvis.local"),
            settings = DesktopSettings(apiGatewayBaseUrl = "https://api.jarvis.local"),
            localRuntimeEndpoint = LocalRuntimeEndpointSnapshot(
                apiGatewayBaseUrl = "https://127.0.0.1:18080",
                reason = "runtime summary says local runtime is healthy"
            )
        )

        assertEquals("https://127.0.0.1:18080", resolved.apiGatewayBaseUrl)
        assertEquals(ConfigSource.ACTIVE_LOCAL_RUNTIME, resolved.apiGatewaySource)
        assertEquals(false, resolved.usesManualEndpointOverride)
    }

    @Test
    @DisplayName("manual override remains stronger than active local runtime")
    fun manualOverrideBeatsActiveLocalRuntime() {
        val resolved = AppConfig.resolve(
            environment = emptyMap(),
            settings = DesktopSettings(
                apiGatewayBaseUrl = "https://api.jarvis.local",
                endpointSelectionMode = EndpointSelectionMode.MANUAL
            ),
            localRuntimeEndpoint = LocalRuntimeEndpointSnapshot(
                apiGatewayBaseUrl = "https://127.0.0.1:18080",
                reason = "runtime summary says local runtime is healthy"
            )
        )

        assertEquals("https://api.jarvis.local", resolved.apiGatewayBaseUrl)
        assertEquals(ConfigSource.MANUAL_PERSISTED_SETTINGS, resolved.apiGatewaySource)
        assertEquals(true, resolved.usesManualEndpointOverride)
    }
}
