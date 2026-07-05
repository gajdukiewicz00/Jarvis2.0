package org.jarvis.desktop.features.sync

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class SyncReadModelTest {

    private fun configFor(server: MockWebServer): () -> ResolvedDesktopConfig {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        return {
            ResolvedDesktopConfig(
                apiGatewayBaseUrl = baseUrl,
                apiBaseUrl = "$baseUrl/api/v1",
                voiceWebSocketUrl = "$baseUrl/ws/voice",
                pcControlWebSocketUrl = "$baseUrl/ws/pc-control",
                locale = Locale.ENGLISH,
                voiceLanguage = "en-US",
                apiGatewaySource = ConfigSource.MANUAL_PERSISTED_SETTINGS,
                apiGatewayReason = "test",
                usesManualEndpointOverride = true
            )
        }
    }

    private fun modelFor(server: MockWebServer) = SyncReadModel(ApiClient(configFor(server)))

    @Test
    fun `pairingStatus returns Available body on success`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "paired": true }""")
        )

        try {
            server.start()
            val result = modelFor(server).pairingStatus()
            val request = server.takeRequest()

            assertEquals("/api/v1/sync/pairing/status", request.path)
            val available = assertInstanceOf(SyncReadModel.Result.Available::class.java, result)
            assertEquals("""{ "paired": true }""", available.body)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `pairingStatus maps failure to Unavailable with reason`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))

        try {
            server.start()
            val result = modelFor(server).pairingStatus()
            val unavailable = assertInstanceOf(SyncReadModel.Result.Unavailable::class.java, result)
            assertTrue(unavailable.reason.contains("Pairing status route is not reachable yet"))
        } finally {
            server.shutdown()
        }
    }
}
