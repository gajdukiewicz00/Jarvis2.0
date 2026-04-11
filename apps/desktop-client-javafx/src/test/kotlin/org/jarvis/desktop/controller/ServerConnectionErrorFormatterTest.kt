package org.jarvis.desktop.controller

import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.util.Locale

class ServerConnectionErrorFormatterTest {

    @Test
    fun `formats null connect exception with pinned manual endpoint hint`() {
        val message = ServerConnectionErrorFormatter.format(
            config = resolvedConfig(
                apiGatewayBaseUrl = "http://localhost:56747",
                apiGatewaySource = ConfigSource.MANUAL_PERSISTED_SETTINGS,
                usesManualEndpointOverride = true
            ),
            throwable = ConnectException()
        )

        assertEquals(
            "Не удалось подключиться к серверу http://localhost:56747. Используется ручной endpoint из настроек. Сервер недоступен или endpoint устарел.",
            message
        )
    }

    @Test
    fun `falls back to endpoint and exception type when message is absent`() {
        val message = ServerConnectionErrorFormatter.format(
            config = resolvedConfig(
                apiGatewayBaseUrl = "https://api.jarvis.local",
                apiGatewaySource = ConfigSource.DEFAULT_INGRESS,
                usesManualEndpointOverride = false
            ),
            throwable = IllegalStateException()
        )

        assertTrue(message.contains("https://api.jarvis.local"))
        assertTrue(message.contains("Тип ошибки: IllegalStateException"))
    }

    private fun resolvedConfig(
        apiGatewayBaseUrl: String,
        apiGatewaySource: ConfigSource,
        usesManualEndpointOverride: Boolean
    ): ResolvedDesktopConfig {
        return ResolvedDesktopConfig(
            apiGatewayBaseUrl = apiGatewayBaseUrl,
            apiBaseUrl = "$apiGatewayBaseUrl/api/v1",
            voiceWebSocketUrl = apiGatewayBaseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws/voice",
            pcControlWebSocketUrl = apiGatewayBaseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws/pc-control",
            locale = Locale.ENGLISH,
            voiceLanguage = "en-US",
            apiGatewaySource = apiGatewaySource,
            apiGatewayReason = "test",
            usesManualEndpointOverride = usesManualEndpointOverride
        )
    }
}
