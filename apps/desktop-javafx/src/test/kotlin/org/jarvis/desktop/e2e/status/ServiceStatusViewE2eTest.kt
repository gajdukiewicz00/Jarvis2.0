package org.jarvis.desktop.e2e.status

import javafx.scene.control.Button
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.status.ServiceStatusReadModel
import org.jarvis.desktop.features.status.ServiceStatusView
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * True headless UI E2E journeys for [ServiceStatusView].
 *
 * The view is driven through a real [ServiceStatusReadModel] pointed at a
 * [MockWebServer] standing in for the api-gateway. Firing the real "Refresh"
 * button runs the same [org.jarvis.agent.status.StatusAggregator] probe path
 * the shell uses, so a constant HTTP response code deterministically produces
 * one [org.jarvis.agent.status.StatusAggregator.ProbeStatus] for every one of
 * the five configured targets. We then assert the visible scene graph reacted:
 * the summary pill's healthy count, the per-service status pills, and the
 * summary breakdown line.
 *
 * Firing the Refresh button (rather than [ServiceStatusView.onRouteActivated])
 * intentionally avoids arming the 15s auto-refresh timer, so no scheduled task
 * needs tearing down beyond shutting the worker executor.
 */
class ServiceStatusViewE2eTest {

    /** Answers every StatusAggregator health target with the same HTTP [code]. */
    private fun constantDispatcher(code: Int): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(code)
    }

    private fun buildView(baseUrl: String): ServiceStatusView = E2eFx.onFx {
        ServiceStatusView(ServiceStatusReadModel(baseUrlProvider = { baseUrl }))
    }

    private fun fireRefresh(view: ServiceStatusView) = E2eFx.onFx {
        E2eFx.findAll<Button>(view).first { it.text == "Refresh" }.fire()
    }

    @Test
    fun `all endpoints returning 200 renders every service Up and a five of five healthy summary`() {
        val server = MockWebServer()
        server.dispatcher = constantDispatcher(200)
        server.start()
        val baseUrl = server.url("/").toString().removeSuffix("/")
        val view = buildView(baseUrl)
        try {
            fireRefresh(view)

            E2eFx.waitForFx(description = "all services up summary") {
                E2eFx.hasText(view, "5/5 services up")
            }

            // Every configured service name is rendered as an "Up" row.
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "backend-api-gateway") }, "gateway row rendered")
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "voice-gateway") }, "voice row rendered")
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "vision-security") }, "vision row rendered")
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "Up") }, "status pills read Up")
            // Summary breakdown line reflects five reachable services.
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "Up 5") }, "summary breakdown counts 5 up")
            // The refresh timestamp replaced the initial placeholder.
            assertFalse(
                E2eFx.onFx { E2eFx.hasText(view, "Waiting for status snapshot") },
                "the updated label should replace the placeholder"
            )
        } finally {
            E2eFx.onFx { view.onShellShutdown() }
            server.shutdown()
        }
    }

    @Test
    fun `a 503 from every endpoint degrades all services and zeroes the healthy count`() {
        val server = MockWebServer()
        server.dispatcher = constantDispatcher(503)
        server.start()
        val baseUrl = server.url("/").toString().removeSuffix("/")
        val view = buildView(baseUrl)
        try {
            fireRefresh(view)

            E2eFx.waitForFx(description = "degraded summary") {
                E2eFx.hasText(view, "0/5 services up")
            }

            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "Degraded") }, "status pills read Degraded")
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "Degraded 5") }, "summary breakdown counts 5 degraded")
        } finally {
            E2eFx.onFx { view.onShellShutdown() }
            server.shutdown()
        }
    }

    @Test
    fun `a 401 from every endpoint counts as reachable-but-protected and stays healthy`() {
        val server = MockWebServer()
        server.dispatcher = constantDispatcher(401)
        server.start()
        val baseUrl = server.url("/").toString().removeSuffix("/")
        val view = buildView(baseUrl)
        try {
            fireRefresh(view)

            // PROTECTED (HTTP 401) is reachable, so the healthy count is still 5/5.
            E2eFx.waitForFx(description = "protected-but-healthy summary") {
                E2eFx.hasText(view, "5/5 services up")
            }

            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "Protected") }, "status pills read Protected")
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "Protected 5") }, "summary breakdown counts 5 protected")
        } finally {
            E2eFx.onFx { view.onShellShutdown() }
            server.shutdown()
        }
    }

    @Test
    fun `an unreachable gateway marks every service Down with an error summary`() {
        // Start then immediately shut down so the URL has nothing listening —
        // every probe fails fast with connection-refused, which maps to DOWN.
        val deadServer = MockWebServer()
        deadServer.start()
        val deadUrl = deadServer.url("/").toString().removeSuffix("/")
        deadServer.shutdown()

        val view = buildView(deadUrl)
        try {
            fireRefresh(view)

            E2eFx.waitForFx(description = "all services down summary") {
                E2eFx.hasText(view, "0/5 services up")
            }

            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "Down") }, "status pills read Down")
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "Down 5") }, "summary breakdown counts 5 down")
        } finally {
            E2eFx.onFx { view.onShellShutdown() }
        }
    }
}
