package org.jarvis.desktop.features.status

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.agent.status.StatusAggregator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
