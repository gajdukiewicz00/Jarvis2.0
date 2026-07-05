package org.jarvis.desktop.features.voice

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class VoiceHelpReadModelTest {

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

    private fun modelFor(server: MockWebServer) = VoiceHelpReadModel(ApiClient(configFor(server)))

    @Test
    fun `helpCatalog parses grouped categories form`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "categories": [
                        { "name": "Audio", "commands": [ { "command": "play music", "description": "starts playback" } ] },
                        { "name": "Empty", "commands": [] }
                      ]
                    }
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val categories = modelFor(server).helpCatalog()

            assertEquals(1, categories.size, "empty categories should be filtered out")
            assertEquals("Audio", categories.first().name)
            assertEquals("play music", categories.first().commands.first().phrase)
            assertEquals("starts playback", categories.first().commands.first().description)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `helpCatalog parses object-of-categories form`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "audio": [ "play music", "stop music" ],
                      "memory": [ { "phrase": "remember this", "detail": "stores a note" } ]
                    }
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val categories = modelFor(server).helpCatalog()

            assertEquals(2, categories.size)
            val audio = categories.first { it.name == "audio" }
            assertEquals(2, audio.commands.size)
            assertEquals("play music", audio.commands[0].phrase)
            val memory = categories.first { it.name == "memory" }
            assertEquals("remember this", memory.commands.first().phrase)
            assertEquals("stores a note", memory.commands.first().description)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `helpCatalog parses flat array of strings under a single Commands category`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""["turn on the lights", "turn off the lights"]""")
        )

        try {
            server.start()
            val categories = modelFor(server).helpCatalog()

            assertEquals(1, categories.size)
            assertEquals("Commands", categories.first().name)
            assertEquals(2, categories.first().commands.size)
            assertEquals("turn on the lights", categories.first().commands[0].phrase)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `helpCatalog parses nested help array form`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "help": [ { "example": "what's the weather", "help": "reports current weather" } ] }""")
        )

        try {
            server.start()
            val categories = modelFor(server).helpCatalog()

            assertEquals(1, categories.size)
            assertEquals("what's the weather", categories.first().commands.first().phrase)
            assertEquals("reports current weather", categories.first().commands.first().description)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `helpCatalog returns empty list when response shape is unrecognized`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "unexpected": "shape" }""")
        )

        try {
            server.start()
            val categories = modelFor(server).helpCatalog()
            assertTrue(categories.isEmpty())
        } finally {
            server.shutdown()
        }
    }
}
