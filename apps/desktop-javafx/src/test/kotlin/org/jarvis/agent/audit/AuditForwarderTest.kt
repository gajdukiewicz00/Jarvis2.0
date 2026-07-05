package org.jarvis.agent.audit

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.commands.agent.AgentEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class AuditForwarderTest {

    @Test
    fun `forwards a translatable event to the audit ingest endpoint`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(202))

        try {
            server.start()
            val feed = AgentLiveFeed()
            val forwarder = AuditForwarder(
                gatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
                agentId = "agent-1",
                feed = feed
            )
            forwarder.start()

            feed.emit(
                AgentEvent.info(
                    "agent-1",
                    AgentEvent.Type.COMMAND_EXECUTED,
                    "executed intent=pc.window.focus",
                    mapOf("commandId" to "cmd-1")
                )
            )

            val request = server.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("/api/v1/audit/ingest", request?.path)
            assertTrue(request?.getHeader("Content-Type")?.startsWith("application/json") == true)
            assertTrue(request!!.body.readUtf8().contains("COMMAND_EXECUTED"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `never forwards diagnostics-only events like ERROR`() {
        val server = MockWebServer()

        try {
            server.start()
            val feed = AgentLiveFeed()
            val forwarder = AuditForwarder(
                gatewayBaseUrl = server.url("/").toString().removeSuffix("/"),
                agentId = "agent-1",
                feed = feed
            )
            forwarder.start()

            feed.emit(AgentEvent.error("agent-1", AgentEvent.Type.ERROR, "boom", emptyMap()))

            // Nothing should ever be posted for a diagnostics-only event type.
            val request = server.takeRequest(500, TimeUnit.MILLISECONDS)
            assertEquals(null, request)
        } finally {
            server.shutdown()
        }
    }
}
