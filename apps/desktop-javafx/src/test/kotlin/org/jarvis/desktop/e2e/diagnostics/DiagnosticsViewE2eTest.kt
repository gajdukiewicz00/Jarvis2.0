package org.jarvis.desktop.e2e.diagnostics

import javafx.scene.control.Button
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.diagnostics.DiagnosticsView
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * True headless UI E2E journeys for [DiagnosticsView].
 *
 * The view is a [javafx.scene.control.ScrollPane]; the shared [E2eFx] graph
 * search descends into its content, so no [javafx.scene.Scene]/skin (which can
 * hang under Monocle) is needed. It is built against a [MockWebServer] standing
 * in for the api-gateway, whose [Dispatcher] answers every desktop endpoint
 * probe so neither construction nor the refresh both hang on an unanswered
 * request.
 *
 * The diagnostics refresh also runs the launcher-side runtime health model,
 * which reads real host paths/ports; that path is side-effect-free and always
 * terminates (each probe is timeout-bounded), so the refresh test uses a
 * generous wait and asserts only the structural content that holds on every
 * machine — the endpoint-probe rows and runtime facts the render populates.
 */
class DiagnosticsViewE2eTest {

    private fun okDispatcher(): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"status":"UP"}""")
    }

    private fun buildView(server: MockWebServer): DiagnosticsView = E2eFx.onFx {
        DiagnosticsView(E2eFx.apiClientFor(server))
    }

    @Test
    fun `construction mounts the static diagnostics skeleton with placeholder pills`() {
        val server = MockWebServer()
        server.dispatcher = okDispatcher()
        server.start()
        val view = buildView(server)
        try {
            E2eFx.onFx {
                // Page + section scaffolding.
                assertTrue(E2eFx.hasText(view, "Diagnostics"), "page title present")
                assertTrue(E2eFx.hasText(view, "Launcher / Runtime Health"), "runtime section present")
                assertTrue(E2eFx.hasText(view, "Desktop / Client Endpoint Probes"), "endpoint section present")
                assertTrue(E2eFx.hasText(view, "Recent Diagnostics Preview"), "log section present")
                // The three summary pills start in their "checking" placeholder state.
                assertTrue(E2eFx.hasText(view, "Checking runtime"), "runtime pill placeholder")
                assertTrue(E2eFx.hasText(view, "Checking endpoints"), "endpoint pill placeholder")
                assertTrue(E2eFx.hasText(view, "Checking logs"), "log pill placeholder")
                // Resolved-targets info grid labels are built up front.
                assertTrue(E2eFx.hasText(view, "API client"), "resolved-targets grid present")
                // Header shows the pre-refresh updated placeholder.
                assertTrue(E2eFx.hasText(view, "Waiting for diagnostics snapshot"), "updated placeholder present")
            }
        } finally {
            E2eFx.onFx { view.onShellShutdown() }
            server.shutdown()
        }
    }

    @Test
    fun `refresh populates the updated timestamp, endpoint probe rows and runtime facts`() {
        val server = MockWebServer()
        server.dispatcher = okDispatcher()
        server.start()
        val view = buildView(server)
        try {
            E2eFx.onFx {
                E2eFx.findAll<Button>(view).first { it.text == "Refresh" }.fire()
            }

            // The refresh worker resolves a snapshot and re-renders; the updated
            // label flips from the placeholder to a formatted "Updated HH:mm:ss".
            E2eFx.waitForFx(timeoutMs = 45_000, description = "diagnostics refreshed") {
                E2eFx.hasText(view, "Updated ")
            }

            assertFalse(
                E2eFx.onFx { E2eFx.hasText(view, "Waiting for diagnostics snapshot") },
                "the updated placeholder should be replaced after refresh"
            )
            // Endpoint probe rows are rendered from the desktop health checker.
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "API Gateway") }, "gateway probe row rendered")
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "Auth Context") }, "auth probe row rendered")
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "Analytics API") }, "analytics probe row rendered")
            // Runtime facts grid is populated by the runtime snapshot render.
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "Backend PID") }, "runtime facts rendered")
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "Feature flags") }, "feature flags fact rendered")
        } finally {
            E2eFx.onFx { view.onShellShutdown() }
            server.shutdown()
        }
    }
}
