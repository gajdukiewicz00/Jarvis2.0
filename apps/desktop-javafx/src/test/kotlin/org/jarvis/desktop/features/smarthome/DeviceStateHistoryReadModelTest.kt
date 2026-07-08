package org.jarvis.desktop.features.smarthome

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class DeviceStateHistoryReadModelTest {

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

    private fun modelFor(server: MockWebServer) = DeviceStateHistoryReadModel(ApiClient(configFor(server)))

    @Test
    fun `history parses entries with every field populated using the default limit`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {
                        "deviceId": "lamp-1", "action": "TURN_ON", "payload": "80",
                        "stateJson": "{\"on\":true,\"brightness\":80}", "success": true,
                        "recordedAt": "2026-07-01T10:00:00Z"
                      },
                      {
                        "deviceId": "lamp-1", "action": "TURN_OFF", "payload": null,
                        "stateJson": "{\"on\":false}", "success": true, "recordedAt": "2026-07-01T09:00:00Z"
                      }
                    ]
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val entries = modelFor(server).history("lamp-1")

            assertEquals(2, entries.size)
            val first = entries[0]
            assertEquals("lamp-1", first.deviceId)
            assertEquals("TURN_ON", first.action)
            assertEquals("80", first.payload)
            assertEquals("{\"on\":true,\"brightness\":80}", first.stateJson)
            assertTrue(first.success)
            assertEquals("2026-07-01T10:00:00Z", first.recordedAt)

            assertNull(entries[1].payload)

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertTrue(request.path!!.contains("/smarthome/devices/lamp-1/state-history"))
            assertTrue(request.path!!.contains("limit=${DeviceStateHistoryReadModel.DEFAULT_LIMIT}"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `history forwards a custom limit in the query string`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""[]"""))

        try {
            server.start()
            modelFor(server).history("lamp-1", limit = 5)

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("limit=5"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `history defaults missing optional fields`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""[{"deviceId": "lock-1", "action": "LOCK"}]""")
        )

        try {
            server.start()
            val entry = modelFor(server).history("lock-1").single()

            assertEquals("lock-1", entry.deviceId)
            assertEquals("LOCK", entry.action)
            assertNull(entry.payload)
            assertEquals("{}", entry.stateJson)
            assertFalse(entry.success)
            assertNull(entry.recordedAt)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `history returns an empty list for an empty array`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""[]"""))

        try {
            server.start()
            assertTrue(modelFor(server).history("lamp-1").isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `history returns an empty list when the payload is not an array`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"error": "not found"}""")
        )

        try {
            server.start()
            assertTrue(modelFor(server).history("lamp-1").isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `history URL-encodes a device id containing spaces`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""[]"""))

        try {
            server.start()
            modelFor(server).history("hall lamp")

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("/smarthome/devices/hall+lamp/state-history"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `history propagates a server error instead of silently degrading`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            server.start()
            val ex = assertThrows(Exception::class.java) { modelFor(server).history("lamp-1") }
            assertTrue(ex.message!!.contains("Server error"))
        } finally {
            server.shutdown()
        }
    }
}
