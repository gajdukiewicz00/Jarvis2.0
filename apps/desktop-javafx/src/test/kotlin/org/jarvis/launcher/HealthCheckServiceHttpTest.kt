package org.jarvis.launcher

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Drives [HealthCheckService]'s real HTTP probing code (actuator + gateway
 * surface checks) against a loopback [MockWebServer]. Every service that
 * would otherwise hit a hard-coded local port is short-circuited via
 * [HealthCheckService.serviceCheckOverride]; only the service under test is
 * left to perform a real request against the mock, keeping the test hermetic.
 */
class HealthCheckServiceHttpTest {

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

    private fun serviceFor(
        server: MockWebServer,
        realService: String
    ): HealthCheckService {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        return HealthCheckService(
            apiBaseUrl = baseUrl,
            kubeconfigProvider = { null },
            onStatusChange = {},
            serviceCheckOverride = { name, _ ->
                when (name) {
                    realService -> null // let this one perform a real HTTP check
                    "api-gateway", "security-service" -> up(name)
                    else -> disabled(name)
                }
            }
        )
    }

    @Test
    fun `api gateway readiness returning UP is reported UP`() {
        val server = MockWebServer()
        server.dispatcher = dispatcher {
            MockResponse().setResponseCode(200).setBody("""{"status":"UP"}""")
        }
        try {
            server.start()
            val service = serviceFor(server, realService = "api-gateway")
            val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)
            val check = status?.coreServices?.get("api-gateway")
            assertNotNull(check)
            assertEquals(
                HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UP,
                check!!.status
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `api gateway falls back from 404 readiness to health endpoint`() {
        val server = MockWebServer()
        server.dispatcher = dispatcher { path ->
            if (path.endsWith("/readiness")) {
                MockResponse().setResponseCode(404)
            } else {
                MockResponse().setResponseCode(200).setBody("""{"status": "UP"}""")
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
    fun `api gateway returning non-UP body is reported DOWN`() {
        val server = MockWebServer()
        server.dispatcher = dispatcher {
            MockResponse().setResponseCode(200).setBody("""{"status":"DOWN"}""")
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
    fun `api gateway HTTP 500 is reported DOWN`() {
        val server = MockWebServer()
        server.dispatcher = dispatcher { MockResponse().setResponseCode(500) }
        try {
            server.start()
            val service = serviceFor(server, realService = "api-gateway")
            val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)
            val check = status?.coreServices?.get("api-gateway")
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
    fun `gateway surface 200 marks optional service UP`() {
        val server = MockWebServer()
        server.dispatcher = dispatcher { MockResponse().setResponseCode(200).setBody("[]") }
        try {
            server.start()
            val service = serviceFor(server, realService = "smart-home-api")
            val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)
            val check = status?.optionalServices?.get("smart-home-api")
            assertEquals(
                HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UP,
                check?.status
            )
            assertTrue(check?.message?.contains("200") == true, check?.message)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `gateway surface 401 is treated as reachable but protected`() {
        val server = MockWebServer()
        server.dispatcher = dispatcher { MockResponse().setResponseCode(401) }
        try {
            server.start()
            val service = serviceFor(server, realService = "smart-home-api")
            val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)
            val check = status?.optionalServices?.get("smart-home-api")
            assertEquals(
                HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.UP,
                check?.status
            )
            assertTrue(check?.message?.contains("protected") == true, check?.message)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `gateway surface 503 marks optional service DOWN`() {
        val server = MockWebServer()
        server.dispatcher = dispatcher { MockResponse().setResponseCode(503) }
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
    fun `gateway surface 404 marks optional service DOWN as missing route`() {
        val server = MockWebServer()
        server.dispatcher = dispatcher { MockResponse().setResponseCode(404) }
        try {
            server.start()
            val service = serviceFor(server, realService = "analytics-api")
            val status = service.checkHealth(backendPid = null, backendExpectedRunning = true)
            val check = status?.optionalServices?.get("analytics-api")
            assertEquals(
                HealthCheckService.ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                check?.status
            )
            assertTrue(check?.message?.contains("route missing") == true, check?.message)
        } finally {
            server.shutdown()
        }
    }
}
