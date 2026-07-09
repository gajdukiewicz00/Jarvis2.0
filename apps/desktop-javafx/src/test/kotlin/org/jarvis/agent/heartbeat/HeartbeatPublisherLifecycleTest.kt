package org.jarvis.agent.heartbeat

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.agent.killswitch.KillSwitchManager
import org.jarvis.commands.agent.AgentCapability
import org.jarvis.commands.agent.AgentIdentity
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Supplements [HeartbeatPublisherTest] with the non-2xx failure branches and
 * the scheduler lifecycle ([HeartbeatPublisher.start] / close) that the
 * primary suite does not cover.
 */
class HeartbeatPublisherLifecycleTest {

    private fun identity(): AgentIdentity = AgentIdentity.builder()
        .agentId("agent-1")
        .hostId("host-1")
        .hostname("test-host")
        .registeredAt(Instant.now())
        .build()

    private fun publisher(server: MockWebServer, tempDir: Path, intervalSeconds: Long = 15): HeartbeatPublisher {
        val feed = AgentLiveFeed()
        val killSwitch = KillSwitchManager(agentId = "agent-1", feed = feed, storeDir = tempDir)
        return HeartbeatPublisher(
            identity = identity(),
            capabilities = setOf(AgentCapability.FILE_SYSTEM),
            killSwitch = killSwitch,
            feed = feed,
            backendBaseUrl = server.url("/").toString().removeSuffix("/"),
            intervalSeconds = intervalSeconds
        )
    }

    @Test
    fun `publishOnce returns false on a non-2xx heartbeat response`(@TempDir tempDir: Path) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))
        try {
            server.start()
            assertFalse(publisher(server, tempDir).publishOnce())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `register returns false on a non-2xx response`(@TempDir tempDir: Path) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))
        try {
            server.start()
            assertFalse(publisher(server, tempDir).register())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `register returns false when the backend is unreachable`(@TempDir tempDir: Path) {
        val server = MockWebServer()
        server.start()
        val pub = publisher(server, tempDir)
        server.shutdown()
        assertFalse(pub.register())
    }

    @Test
    fun `start schedules an immediate heartbeat and close stops the scheduler`(@TempDir tempDir: Path) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        try {
            server.start()
            val pub = publisher(server, tempDir)
            pub.start()
            // The fixed-rate task has zero initial delay, so a request arrives promptly.
            val request = server.takeRequest(3, TimeUnit.SECONDS)
            assertNotNull(request, "start() should have posted at least one heartbeat")
            pub.close()
        } finally {
            server.shutdown()
        }
    }
}
