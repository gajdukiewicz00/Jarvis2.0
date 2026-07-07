package org.jarvis.desktop.features.status

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.agent.status.StatusAggregator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ServiceStatusReadModelTest {

    private fun dispatcherReturning(degradedPath: String) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            return if (request.path == degradedPath) {
                MockResponse().setResponseCode(503)
            } else {
                MockResponse().setResponseCode(200)
            }
        }
    }

    private fun service(name: String, status: StatusAggregator.ProbeStatus, detail: String? = null) =
        StatusAggregator.ServiceStatus(name, status, detail)

    @Test
    fun `refresh returns a snapshot with every configured service sorted by name`() {
        val server = MockWebServer()
        server.dispatcher = dispatcherReturning("/api/v1/voice/runtime")

        try {
            server.start()
            val baseUrl = server.url("/").toString().removeSuffix("/")
            val readModel = ServiceStatusReadModel(baseUrlProvider = { baseUrl })

            val snapshot = readModel.refresh()

            assertEquals(baseUrl, snapshot.baseUrl)
            assertEquals(5, snapshot.services.size)
            assertEquals(snapshot.services.sortedBy { it.name }, snapshot.services)
            assertTrue(
                snapshot.services.any {
                    it.name == "voice-gateway" && it.status == StatusAggregator.ProbeStatus.DEGRADED
                }
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `refresh rebuilds the aggregator when the base URL changes`() {
        val serverA = MockWebServer()
        val serverB = MockWebServer()
        serverA.dispatcher = dispatcherReturning("/actuator/health")
        serverB.dispatcher = dispatcherReturning("/does-not-exist")

        try {
            serverA.start()
            serverB.start()
            var activeBaseUrl = serverA.url("/").toString().removeSuffix("/")
            val readModel = ServiceStatusReadModel(baseUrlProvider = { activeBaseUrl })

            val first = readModel.refresh()
            assertTrue(
                first.services.any {
                    it.name == "backend-api-gateway" && it.status == StatusAggregator.ProbeStatus.DEGRADED
                }
            )

            activeBaseUrl = serverB.url("/").toString().removeSuffix("/")
            val second = readModel.refresh()
            assertEquals(activeBaseUrl, second.baseUrl)
            assertTrue(
                second.services.any {
                    it.name == "backend-api-gateway" && it.status == StatusAggregator.ProbeStatus.UP
                }
            )
        } finally {
            serverA.shutdown()
            serverB.shutdown()
        }
    }

    @Test
    fun `healthyCount treats PROTECTED services as healthy alongside UP`() {
        val snapshot = ServiceStatusReadModel.Snapshot(
            refreshedAt = Instant.now(),
            baseUrl = "http://example.invalid",
            services = listOf(
                service("backend-api-gateway", StatusAggregator.ProbeStatus.UP),
                service("voice-gateway", StatusAggregator.ProbeStatus.PROTECTED, "HTTP 401"),
                service("llm-service", StatusAggregator.ProbeStatus.DEGRADED, "HTTP 503"),
                service("memory-service", StatusAggregator.ProbeStatus.DOWN, "IOException")
            )
        )

        assertEquals(2, snapshot.healthyCount)
    }

    @Test
    fun `downServices excludes PROTECTED and only lists DEGRADED or DOWN services`() {
        val protectedService = service("voice-gateway", StatusAggregator.ProbeStatus.PROTECTED, "HTTP 403")
        val degradedService = service("llm-service", StatusAggregator.ProbeStatus.DEGRADED, "HTTP 503")
        val downService = service("memory-service", StatusAggregator.ProbeStatus.DOWN, "IOException")
        val snapshot = ServiceStatusReadModel.Snapshot(
            refreshedAt = Instant.now(),
            baseUrl = "http://example.invalid",
            services = listOf(
                service("backend-api-gateway", StatusAggregator.ProbeStatus.UP),
                protectedService,
                degradedService,
                downService
            )
        )

        assertEquals(listOf(degradedService, downService), snapshot.downServices)
        assertTrue(protectedService !in snapshot.downServices)
    }

    @Test
    fun `healthyCount and downServices agree when every service is fully up`() {
        val snapshot = ServiceStatusReadModel.Snapshot(
            refreshedAt = Instant.now(),
            baseUrl = "http://example.invalid",
            services = listOf(
                service("backend-api-gateway", StatusAggregator.ProbeStatus.UP),
                service("voice-gateway", StatusAggregator.ProbeStatus.UP)
            )
        )

        assertEquals(2, snapshot.healthyCount)
        assertTrue(snapshot.downServices.isEmpty())
    }

    @Test
    fun `toStatusLevel maps every ProbeStatus onto the canonical vocabulary`() {
        assertEquals(StatusLevel.UP, StatusAggregator.ProbeStatus.UP.toStatusLevel())
        assertEquals(StatusLevel.PROTECTED, StatusAggregator.ProbeStatus.PROTECTED.toStatusLevel())
        assertEquals(StatusLevel.DEGRADED, StatusAggregator.ProbeStatus.DEGRADED.toStatusLevel())
        assertEquals(StatusLevel.DOWN, StatusAggregator.ProbeStatus.DOWN.toStatusLevel())
    }

    @Test
    fun `toStatusLevel agrees with ProbeStatus isReachable on what counts as healthy`() {
        // The two systems must never contradict: whatever ProbeStatus.isReachable
        // already treats as "alive", StatusLevel must treat as "healthy".
        StatusAggregator.ProbeStatus.values().forEach { status ->
            assertEquals(
                status.isReachable,
                status.toStatusLevel().isHealthy,
                "ProbeStatus.$status: isReachable=${status.isReachable} but " +
                    "toStatusLevel()=${status.toStatusLevel()} isHealthy=${status.toStatusLevel().isHealthy}"
            )
        }
    }
}
