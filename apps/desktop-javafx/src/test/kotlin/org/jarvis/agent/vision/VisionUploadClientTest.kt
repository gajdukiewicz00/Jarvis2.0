package org.jarvis.agent.vision

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.commands.agent.AgentEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VisionUploadClientTest {

    @Test
    fun `publishFrame posts metadata and returns the frameId on success`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"frameId":"frame-123"}"""))

        try {
            server.start()
            val feed = AgentLiveFeed()
            val client = VisionUploadClient(
                visionBaseUrl = server.url("/").toString().removeSuffix("/"),
                agentId = "agent-1",
                userId = "owner",
                feed = feed
            )

            val frameId = client.publishFrame(captureType = "screenshot", contextWindow = "Editor")
            val request = server.takeRequest()

            assertEquals("frame-123", frameId)
            assertEquals("/api/v1/vision/frames", request.path)
            assertTrue(request.body.readUtf8().contains("\"captureType\":\"screenshot\""))
            assertEquals(AgentEvent.Type.CV_EVENT_RECEIVED, feed.recent().last().type)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `publishFrame returns null and emits nothing when the server rejects the upload`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            server.start()
            val feed = AgentLiveFeed()
            val client = VisionUploadClient(
                visionBaseUrl = server.url("/").toString().removeSuffix("/"),
                agentId = "agent-1",
                userId = "owner",
                feed = feed
            )

            val frameId = client.publishFrame(captureType = "screenshot")
            assertNull(frameId)
            assertEquals(0, feed.size())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `publishFrame returns null when the connection fails rather than throwing`() {
        val server = MockWebServer()
        server.start()
        val deadUrl = server.url("/").toString().removeSuffix("/")
        server.shutdown()

        val client = VisionUploadClient(
            visionBaseUrl = deadUrl,
            agentId = "agent-1",
            userId = "owner",
            feed = AgentLiveFeed()
        )

        assertNull(client.publishFrame(captureType = "screenshot"))
    }
}
