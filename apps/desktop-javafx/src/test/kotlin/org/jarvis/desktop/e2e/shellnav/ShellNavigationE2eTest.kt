package org.jarvis.desktop.e2e.shellnav

import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.ToggleButton
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.controlcenter.ControlCenterView
import org.jarvis.desktop.features.status.ServiceStatusReadModel
import org.jarvis.desktop.features.status.ServiceStatusView
import org.jarvis.desktop.shell.ShellNavPane
import org.jarvis.desktop.shell.ShellNavigator
import org.jarvis.desktop.shell.ShellRoute
import org.jarvis.desktop.shell.ShellRouteContent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * True UI end-to-end journeys for shell navigation.
 *
 * These wire the REAL [ShellNavigator] + [ShellNavPane] to a content host that
 * mirrors [org.jarvis.desktop.shell.ShellRoot.showRoute] (StackPane.setAll of the
 * routed view, then [ShellRouteContent.onRouteActivated]). Each journey fires the
 * real navigation ToggleButton a user would click and asserts BOTH that the visible
 * center content swapped to the selected feature view AND — for network-backed
 * screens — that the backend received the expected health probes.
 */
class ShellNavigationE2eTest {

    /** A dispatcher answering every StatusAggregator health target with [code]. */
    private fun constantDispatcher(code: Int): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(code)
    }

    /**
     * Force a CSS + layout pass so control skins (e.g. the ScrollPane viewport that
     * holds a routed view's content) materialize in the scene graph. Without a shown
     * Stage there is no pulse, so we drive one deterministically. Must run on the FX thread.
     */
    private fun realize(node: Node) {
        node.applyCss()
        (node as? javafx.scene.Parent)?.layout()
    }

    /**
     * Build the navigator + nav pane + a content host wired exactly like ShellRoot:
     * navigating swaps the center node and activates it. Views are cached per route.
     * Returns the assembled pieces so the test can drive the pane and inspect the host.
     */
    private class NavHarness(
        val navigator: ShellNavigator,
        val navPane: ShellNavPane,
        val contentHost: StackPane,
        private val viewFactory: (ShellRoute) -> Node
    ) {
        private val views = mutableMapOf<ShellRoute, Node>()

        fun viewFor(route: ShellRoute): Node = views.getOrPut(route) { viewFactory(route) }

        fun install() {
            navigator.addListener { route ->
                val node = viewFor(route)
                contentHost.children.setAll(node)
                (node as? ShellRouteContent)?.onRouteActivated()
            }
        }

        fun navButton(route: ShellRoute): ToggleButton =
            E2eFx.findAll<ToggleButton>(navPane).first { it.text == route.navLabel }

        fun dispose() {
            views.values.forEach { (it as? ShellRouteContent)?.onShellShutdown() }
            navPane.dispose()
        }
    }

    private fun buildHarness(statusBaseUrl: String): NavHarness {
        val navigator = ShellNavigator(ShellRoute.CONTROL_CENTER)
        val visible = listOf(ShellRoute.CONTROL_CENTER, ShellRoute.SERVICE_STATUS)
        val navPane = ShellNavPane(navigator, visible)
        val host = StackPane()
        val harness = NavHarness(navigator, navPane, host) { route ->
            when (route) {
                ShellRoute.SERVICE_STATUS ->
                    ServiceStatusView(ServiceStatusReadModel(baseUrlProvider = { statusBaseUrl }))
                ShellRoute.CONTROL_CENTER ->
                    ControlCenterView(
                        onNavigate = { navigator.navigateTo(it) },
                        serviceStatusReadModel = ServiceStatusReadModel(baseUrlProvider = { statusBaseUrl })
                    )
                else -> javafx.scene.control.Label(route.title)
            }
        }
        // Realistic layout so the pane/host live in a Scene like the real shell.
        Scene(HBox(navPane, host))
        harness.install() // initial listener fire activates CONTROL_CENTER (the landing screen)
        return harness
    }

    @Test
    fun `clicking a nav button swaps center content to the selected feature view and probes the backend`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        val server = MockWebServer()
        server.dispatcher = constantDispatcher(200)
        server.start()
        val base = server.url("/").toString().removeSuffix("/")

        val harness = E2eFx.onFx { buildHarness(base) }
        try {
            // Landing screen is Control Center before any navigation.
            E2eFx.onFx { assertEquals(ShellRoute.CONTROL_CENTER, harness.navigator.currentRoute()) }
            E2eFx.waitForFx(description = "control center is the initial center content") {
                realize(harness.contentHost)
                E2eFx.hasText(harness.contentHost, "Control Center")
            }

            // User clicks the "Service Status" nav button.
            E2eFx.onFx { harness.navButton(ShellRoute.SERVICE_STATUS).fire() }

            // Center content swaps to the ServiceStatusView, which loads live health.
            E2eFx.waitForFx(description = "service status summary rendered") {
                realize(harness.contentHost)
                E2eFx.hasText(harness.contentHost, "5/5 services up")
            }

            E2eFx.onFx {
                realize(harness.contentHost)
                // Navigator + nav pane selection reflect the new route (visible reaction).
                assertEquals(ShellRoute.SERVICE_STATUS, harness.navigator.currentRoute())
                assertTrue(
                    harness.navButton(ShellRoute.SERVICE_STATUS).isSelected,
                    "the Service Status toggle should be selected"
                )
                assertFalse(
                    harness.navButton(ShellRoute.CONTROL_CENTER).isSelected,
                    "the Control Center toggle should no longer be selected"
                )
                // Center content is now the ServiceStatusView and no longer Control Center.
                assertNotNull(
                    E2eFx.find<ServiceStatusView>(harness.contentHost),
                    "content host should hold the ServiceStatusView"
                )
                assertTrue(E2eFx.hasText(harness.contentHost, "backend-api-gateway"))
                assertFalse(
                    E2eFx.hasText(harness.contentHost, "All Features"),
                    "Control Center feature index should have been swapped out"
                )
            }

            // Backend actually received the health probes (5 targets on the first refresh).
            assertTrue(server.requestCount >= 5, "expected the 5 health targets to be probed")
            val first = server.takeRequest()
            assertEquals("GET", first.method)
            assertNotNull(first.path)
        } finally {
            E2eFx.onFx { harness.dispose() }
            server.shutdown()
        }
    }

    @Test
    fun `navigating back to Control Center swaps the feature view out again`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        val server = MockWebServer()
        server.dispatcher = constantDispatcher(200)
        server.start()
        val base = server.url("/").toString().removeSuffix("/")

        val harness = E2eFx.onFx { buildHarness(base) }
        try {
            E2eFx.onFx { harness.navButton(ShellRoute.SERVICE_STATUS).fire() }
            E2eFx.waitForFx(description = "service status shown") {
                E2eFx.find<ServiceStatusView>(harness.contentHost) != null
            }

            // Now click back to Control Center — center content must swap back.
            E2eFx.onFx { harness.navButton(ShellRoute.CONTROL_CENTER).fire() }
            E2eFx.waitForFx(description = "control center shown again") {
                realize(harness.contentHost)
                E2eFx.hasText(harness.contentHost, "Control Center") &&
                    E2eFx.find<ServiceStatusView>(harness.contentHost) == null
            }

            E2eFx.onFx {
                assertEquals(ShellRoute.CONTROL_CENTER, harness.navigator.currentRoute())
                assertTrue(harness.navButton(ShellRoute.CONTROL_CENTER).isSelected)
            }
        } finally {
            E2eFx.onFx { harness.dispose() }
            server.shutdown()
        }
    }

    @Test
    fun `service status view surfaces a degraded summary when the backend returns 500`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        val server = MockWebServer()
        server.dispatcher = constantDispatcher(500)
        server.start()
        val base = server.url("/").toString().removeSuffix("/")

        val view = E2eFx.onFx {
            ServiceStatusView(ServiceStatusReadModel(baseUrlProvider = { base })).also { Scene(StackPane(it)) }
        }
        try {
            E2eFx.onFx { view.onRouteActivated() }

            // Every health target is DEGRADED (HTTP 500) => zero healthy of five.
            E2eFx.waitForFx(description = "degraded summary rendered") {
                realize(view)
                E2eFx.hasText(view, "0/5 services up")
            }
            E2eFx.onFx {
                realize(view)
                assertTrue(E2eFx.hasText(view, "Degraded 5"), "summary label should report 5 degraded")
                assertTrue(E2eFx.hasText(view, "HTTP 500"), "per-service detail should show the 500")
            }

            assertTrue(server.requestCount >= 5, "the failing backend should still have been probed")
        } finally {
            E2eFx.onFx { view.onShellShutdown() }
            server.shutdown()
        }
    }
}
