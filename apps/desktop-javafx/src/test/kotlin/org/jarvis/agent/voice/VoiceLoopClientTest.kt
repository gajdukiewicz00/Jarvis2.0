package org.jarvis.agent.voice

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.commands.agent.AgentEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceLoopClientTest {

    private fun clientFor(server: MockWebServer, feed: AgentLiveFeed = AgentLiveFeed()) = VoiceLoopClient(
        voiceGatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
        agentId = "agent-1",
        userId = "owner",
        feed = feed
    )

    @Test
    fun `startSession parses the session and emits VOICE_SESSION_STARTED`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"sessionId":"vs-1","agentId":"agent-1","userId":"owner"}"""))

        try {
            server.start()
            val feed = AgentLiveFeed()
            val session = clientFor(server, feed).startSession()
            val request = server.takeRequest()

            assertEquals("/api/v1/voice/sessions", request.path)
            assertEquals("vs-1", session?.sessionId)
            assertEquals(AgentEvent.Type.VOICE_SESSION_STARTED, feed.recent().last().type)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `startSession returns null and emits nothing on a non-2xx response`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            server.start()
            val feed = AgentLiveFeed()
            val session = clientFor(server, feed).startSession()

            assertNull(session)
            assertEquals(0, feed.size())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `submitUtterance posts transcript and locale and emits INTENT_CLASSIFIED`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"sessionId":"vs-1","sessionStatus":"CLASSIFIED","intent":{"intent":"pc.shutdown","source":"router"}}"""
            )
        )

        try {
            server.start()
            val feed = AgentLiveFeed()
            val reply = clientFor(server, feed).submitUtterance("vs-1", "turn off my computer")
            val request = server.takeRequest()

            assertEquals("/api/v1/voice/sessions/vs-1/utterance", request.path)
            assertTrue(request.body.readUtf8().contains("\"locale\":\"ru\""))
            assertEquals("pc.shutdown", reply?.intent?.intent)
            assertEquals(AgentEvent.Type.INTENT_CLASSIFIED, feed.recent().last().type)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `endSession returns true on success and false on failure`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(404))

        try {
            server.start()
            val client = clientFor(server)
            assertTrue(client.endSession("vs-1"))
            assertTrue(!client.endSession("vs-2"))
        } finally {
            server.shutdown()
        }
    }
}
