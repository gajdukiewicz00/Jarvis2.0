package org.jarvis.agent.status

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StatusAggregatorTest {

    @Test
    fun `refresh maps every configured target to a status keyed by service name`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/actuator/health" -> MockResponse().setResponseCode(200)
                    "/api/v1/voice/runtime" -> MockResponse().setResponseCode(503)
                    else -> MockResponse().setResponseCode(200)
                }
            }
        }

        try {
            server.start()
            val aggregator = StatusAggregator(backendBaseUrl = server.url("/").toString().removeSuffix("/"))
            val snapshot = aggregator.refresh()

            assertEquals(5, snapshot.size)
            assertEquals(StatusAggregator.ProbeStatus.UP, snapshot["backend-api-gateway"]!!.status)
            assertEquals(StatusAggregator.ProbeStatus.DEGRADED, snapshot["voice-gateway"]!!.status)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `refresh marks every target DOWN when the backend is unreachable`() {
        val server = MockWebServer()
        server.start()
        val deadUrl = server.url("/").toString().removeSuffix("/")
        server.shutdown()

        val aggregator = StatusAggregator(backendBaseUrl = deadUrl)
        val snapshot = aggregator.refresh()

        assertEquals(5, snapshot.size)
        snapshot.values.forEach { assertEquals(StatusAggregator.ProbeStatus.DOWN, it.status) }
    }

    @Test
    fun `snapshot reflects the most recent refresh`() {
        val server = MockWebServer()
        server.start()
        val deadUrl = server.url("/").toString().removeSuffix("/")
        server.shutdown()

        val aggregator = StatusAggregator(backendBaseUrl = deadUrl)
        assertEquals(0, aggregator.snapshot.size, "snapshot is empty before the first refresh")
        aggregator.refresh()
        assertEquals(5, aggregator.snapshot.size)
    }
}
