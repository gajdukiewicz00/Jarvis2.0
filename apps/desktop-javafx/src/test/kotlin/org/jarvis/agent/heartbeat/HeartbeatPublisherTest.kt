package org.jarvis.agent.heartbeat

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.agent.killswitch.KillSwitchManager
import org.jarvis.commands.agent.AgentCapability
import org.jarvis.commands.agent.AgentIdentity
import org.jarvis.commands.agent.AgentStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class HeartbeatPublisherTest {

    private fun identity(): AgentIdentity = AgentIdentity.builder()
        .agentId("agent-1")
        .hostId("host-1")
        .hostname("test-host")
        .registeredAt(Instant.now())
        .build()

    @Test
    fun `publishOnce posts a READY heartbeat when the kill switch is clear`(@TempDir tempDir: Path) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))

        try {
            server.start()
            val feed = AgentLiveFeed()
            val killSwitch = KillSwitchManager(agentId = "agent-1", feed = feed, storeDir = tempDir)
            val publisher = HeartbeatPublisher(
                identity = identity(),
                capabilities = setOf(AgentCapability.FILE_SYSTEM),
                killSwitch = killSwitch,
                feed = feed,
                backendBaseUrl = server.url("/").toString().removeSuffix("/")
            )

            val ok = publisher.publishOnce()
            val request = server.takeRequest()

            assertTrue(ok)
            assertEquals("/api/v1/agent/heartbeat", request.path)
            assertEquals("agent-1", request.getHeader("X-Agent-Id"))
            assertTrue(request.body.readUtf8().contains("\"READY\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `publishOnce reports KILL_SWITCH status while engaged`(@TempDir tempDir: Path) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))

        try {
            server.start()
            val feed = AgentLiveFeed()
            val killSwitch = KillSwitchManager(agentId = "agent-1", feed = feed, storeDir = tempDir)
            killSwitch.engage("operator", "unsafe")

            val publisher = HeartbeatPublisher(
                identity = identity(),
                capabilities = emptySet(),
                killSwitch = killSwitch,
                feed = feed,
                backendBaseUrl = server.url("/").toString().removeSuffix("/")
            )

            publisher.publishOnce()
            val request = server.takeRequest()
            assertTrue(request.body.readUtf8().contains("\"KILL_SWITCH\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `overrideStatus takes precedence over the computed status`(@TempDir tempDir: Path) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))

        try {
            server.start()
            val feed = AgentLiveFeed()
            val killSwitch = KillSwitchManager(agentId = "agent-1", feed = feed, storeDir = tempDir)
            val publisher = HeartbeatPublisher(
                identity = identity(),
                capabilities = emptySet(),
                killSwitch = killSwitch,
                feed = feed,
                backendBaseUrl = server.url("/").toString().removeSuffix("/")
            )

            publisher.overrideStatus(AgentStatus.BOOTING)
            publisher.publishOnce()

            val request = server.takeRequest()
            assertTrue(request.body.readUtf8().contains("\"BOOTING\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `publishOnce returns false when the backend is unreachable`(@TempDir tempDir: Path) {
        val server = MockWebServer()
        server.start()
        val deadUrl = server.url("/").toString().removeSuffix("/")
        server.shutdown()

        val feed = AgentLiveFeed()
        val killSwitch = KillSwitchManager(agentId = "agent-1", feed = feed, storeDir = tempDir)
        val publisher = HeartbeatPublisher(
            identity = identity(),
            capabilities = emptySet(),
            killSwitch = killSwitch,
            feed = feed,
            backendBaseUrl = deadUrl
        )

        assertFalse(publisher.publishOnce())
    }

    @Test
    fun `register posts the identity payload and reports success on 2xx`(@TempDir tempDir: Path) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201))

        try {
            server.start()
            val feed = AgentLiveFeed()
            val killSwitch = KillSwitchManager(agentId = "agent-1", feed = feed, storeDir = tempDir)
            val publisher = HeartbeatPublisher(
                identity = identity(),
                capabilities = emptySet(),
                killSwitch = killSwitch,
                feed = feed,
                backendBaseUrl = server.url("/").toString().removeSuffix("/")
            )

            assertTrue(publisher.register())
            val request = server.takeRequest()
            assertEquals("/api/v1/agent/register", request.path)
            assertTrue(request.body.readUtf8().contains("agent-1"))
        } finally {
            server.shutdown()
        }
    }
}
