package org.jarvis.launcher

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Second-wave HTTP coverage for [HealthCheckService]: exercises the
 * gateway-surface response codes and the actuator URL-list fallback branches
 * that the first-wave [HealthCheckServiceHttpTest] left uncovered. Only the
 * service under test performs a real request against a loopback
 * [MockWebServer]; every other service is short-circuited via
 * [HealthCheckService.serviceCheckOverride], so the tests stay hermetic.
 */
class HealthCheckServiceGatewayProbeTest {

    private fun up(name: String) = HealthCheckService.ServiceHealthStatus.ServiceCheck(
        name = name,
        status = HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UP,
        message = "UP"
    )

    private fun disabled(name: String) = HealthCheckService.ServiceHealthStatus.ServiceCheck(
        name = name,
        status = HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN,
        message = "disabled",
        isDisabled = true
    )

    private fun dispatcher(handler: (String) -> MockResponse): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handler(request.path ?: "")
        }

    private fun serviceFor(server: MockWebServer, realService: String): HealthCheckService {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        return HealthCheckService(
            apiBaseUrl = baseUrl,
            kubeconfigProvider = { null },
            onStatusChange = {},
            serviceCheckOverride = { name, _ ->
                when (name) {
                    realService -> null
                    "api-gateway", "security-service" -> up(name)
                    else -> disabled(name)
                }
            }
        )
    }

    @Test
    fun `gateway surface HTTP 502 is reported as upstream failure DOWN`() {
        val server = MockWebServer()
        server.dispatcher = dispatcher { MockResponse().setResponseCode(502) }
        try {
            server.start()
            val service = serviceFor(server, realService = "smart-home-api")
            val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)
            val check = status?.optionalServices?.get("smart-home-api")
            assertEquals(
                HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                check?.status
            )
            assertTrue(check?.message?.contains("upstream") == true, check?.message)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `gateway surface HTTP 504 is reported as upstream failure DOWN`() {
        val server = MockWebServer()
        server.dispatcher = dispatcher { MockResponse().setResponseCode(504) }
        try {
            server.start()
            val service = serviceFor(server, realService = "life-api")
            val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)
            val check = status?.optionalServices?.get("life-api")
            assertEquals(
                HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                check?.status
            )
            assertTrue(check?.message?.contains("upstream") == true, check?.message)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `gateway surface unexpected HTTP 500 falls to else branch DOWN`() {
        val server = MockWebServer()
        server.dispatcher = dispatcher { MockResponse().setResponseCode(500) }
        try {
            server.start()
            val service = serviceFor(server, realService = "analytics-api")
            val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)
            val check = status?.optionalServices?.get("analytics-api")
            assertEquals(
                HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                check?.status
            )
            assertTrue(check?.message?.contains("500") == true, check?.message)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `actuator readiness and health both 404 yields final DOWN with last status`() {
        val server = MockWebServer()
        server.dispatcher = dispatcher { MockResponse().setResponseCode(404) }
        try {
            server.start()
            val service = serviceFor(server, realService = "api-gateway")
            val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)
            val check = status?.coreServices?.get("api-gateway")
            assertEquals(
                HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                check?.status
            )
            assertTrue(check?.message?.contains("404") == true, check?.message)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `actuator readiness 405 falls through to health endpoint UP`() {
        val server = MockWebServer()
        server.dispatcher = dispatcher { path ->
            if (path.endsWith("/readiness")) {
                MockResponse().setResponseCode(405)
            } else {
                MockResponse().setResponseCode(200).setBody("""{"status":"UP"}""")
            }
        }
        try {
            server.start()
            val service = serviceFor(server, realService = "api-gateway")
            val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)
            assertEquals(
                HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UP,
                status?.coreServices?.get("api-gateway")?.status
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `actuator readiness 404 then health 200 non-UP body reported DOWN`() {
        val server = MockWebServer()
        server.dispatcher = dispatcher { path ->
            if (path.endsWith("/readiness")) {
                MockResponse().setResponseCode(404)
            } else {
                MockResponse().setResponseCode(200).setBody("""{"status":"DOWN"}""")
            }
        }
        try {
            server.start()
            val service = serviceFor(server, realService = "api-gateway")
            val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)
            val check = status?.coreServices?.get("api-gateway")
            assertEquals(
                HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                check?.status
            )
            assertTrue(check?.message?.contains("Status not UP") == true, check?.message)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `actuator response with spaced lowercase up status is accepted as UP`() {
        val server = MockWebServer()
        server.dispatcher = dispatcher {
            MockResponse().setResponseCode(200).setBody("""{"status": "up"}""")
        }
        try {
            server.start()
            val service = serviceFor(server, realService = "api-gateway")
            val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)
            assertEquals(
                HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UP,
                status?.coreServices?.get("api-gateway")?.status
            )
        } finally {
            server.shutdown()
        }
    }
}
