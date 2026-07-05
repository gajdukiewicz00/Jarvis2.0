package org.jarvis.desktop.features.smarthome

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class ScenesReadModelTest {

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

    private fun modelFor(server: MockWebServer) = ScenesReadModel(ApiClient(configFor(server)))

    @Test
    fun `loadScenes parses a list of scenes with steps`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {"name": "movie-night", "steps": [
                        {"deviceId": "lamp-1", "action": "DIM", "payload": "20"},
                        {"deviceId": "lock-1", "action": "LOCK"}
                      ]}
                    ]
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val scenes = modelFor(server).loadScenes()

            assertEquals(1, scenes.size)
            assertEquals("movie-night", scenes[0].name)
            assertEquals(2, scenes[0].steps.size)
            assertEquals("lamp-1", scenes[0].steps[0].deviceId)
            assertEquals("20", scenes[0].steps[0].payload)
            assertNull(scenes[0].steps[1].payload)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadScenes returns empty list when the payload is not an array`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "error": "not found" }""")
        )

        try {
            server.start()
            assertTrue(modelFor(server).loadScenes().isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `createScene posts steps with null payload encoded explicitly`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"name": "good-morning", "steps": []}""")
        )

        try {
            server.start()
            val scene = modelFor(server).createScene(
                "  good-morning  ",
                listOf(ScenesReadModel.SceneStep("blinds-1", "OPEN", null))
            )

            assertEquals("good-morning", scene.name)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"payload\":null"))
            assertTrue(body.contains("good-morning"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `deleteScene issues a DELETE to the encoded scene path`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(""))

        try {
            server.start()
            modelFor(server).deleteScene("movie night")

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertTrue(request.path!!.contains("/smarthome/scenes/movie"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `activateScene summarizes successful steps`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "applied": 2,
                      "results": [
                        {"device": {"id": "lamp-1"}, "action": "DIM"},
                        {"deviceId": "lock-1", "action": "LOCK"}
                      ]
                    }
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val result = modelFor(server).activateScene("movie-night")

            assertEquals(2, result.applied)
            assertTrue(result.summary.contains("lamp-1 DIM"))
            assertTrue(result.summary.contains("lock-1 LOCK"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `activateScene reports per-step failures without throwing`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"applied": 0, "results": [{"deviceId": "lamp-1", "error": "unreachable"}]}"""
                )
        )

        try {
            server.start()
            val result = modelFor(server).activateScene("movie-night")

            assertEquals(0, result.applied)
            assertEquals("lamp-1 failed: unreachable", result.summary)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `activateScene reports no steps applied when results are empty`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"applied": 0, "results": []}""")
        )

        try {
            server.start()
            val result = modelFor(server).activateScene("empty-scene")
            assertEquals("no steps applied", result.summary)
        } finally {
            server.shutdown()
        }
    }
}
