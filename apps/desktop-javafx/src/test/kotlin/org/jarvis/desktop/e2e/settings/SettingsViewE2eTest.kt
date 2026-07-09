package org.jarvis.desktop.e2e.settings

import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.TextField
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.settings.SettingsView
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * True headless UI E2E journeys for [SettingsView].
 *
 * The view is a [javafx.scene.control.ScrollPane]; the shared [E2eFx] graph
 * search descends into its content, so it is exercised without a
 * [javafx.scene.Scene]/skin (which can hang under Monocle). Each test builds
 * the real view against a [MockWebServer]-backed [org.jarvis.desktop.api.ApiClient]
 * and fires the actual controls a user clicks, asserting the visible scene graph
 * reacted.
 *
 * These journeys deliberately avoid the "Save changes" button — it persists to
 * the real desktop preferences store — and there are no modal dialogs to trip
 * over. [SettingsView.onShellShutdown] is always called to detach the AppConfig
 * listener and stop the worker.
 */
class SettingsViewE2eTest {

    private fun okDispatcher(): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"status":"UP"}""")
    }

    private fun buildView(server: MockWebServer, onLogout: () -> Unit = {}): SettingsView = E2eFx.onFx {
        SettingsView(E2eFx.apiClientFor(server), onLogout = onLogout)
    }

    @Test
    fun `construction mounts every settings card and its action buttons`() {
        val server = MockWebServer()
        server.dispatcher = okDispatcher()
        server.start()
        val view = buildView(server)
        try {
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Settings"), "page title present")
                assertTrue(E2eFx.hasText(view, "Environment and endpoint policy"), "environment card present")
                assertTrue(E2eFx.hasText(view, "Resolved endpoints"), "resolved endpoints card present")
                assertTrue(E2eFx.hasText(view, "General settings"), "general settings card present")
                assertTrue(E2eFx.hasText(view, "Service checks"), "service checks card present")
                assertTrue(E2eFx.hasText(view, "Account and session"), "account card present")
                assertTrue(E2eFx.hasText(view, "About and build info"), "about card present")
                // Action buttons + starting-state pill.
                assertTrue(E2eFx.hasText(view, "Save changes"), "save button present")
                assertTrue(E2eFx.hasText(view, "Re-check services"), "recheck button present")
                assertTrue(E2eFx.hasText(view, "Logout"), "logout button present")
                assertTrue(E2eFx.hasText(view, "Pin API Gateway URL manually"), "manual override control present")
                assertTrue(E2eFx.hasText(view, "Checks idle"), "service summary pill starts idle")
            }
        } finally {
            E2eFx.onFx { view.onShellShutdown() }
            server.shutdown()
        }
    }

    @Test
    fun `toggling the manual override checkbox enables and disables the gateway URL field`() {
        val server = MockWebServer()
        server.dispatcher = okDispatcher()
        server.start()
        val view = buildView(server)
        try {
            val checkBox = E2eFx.onFx {
                E2eFx.findAll<CheckBox>(view).first { it.text.contains("Pin API Gateway", ignoreCase = true) }
            }
            val field = E2eFx.onFx { E2eFx.find<TextField>(view)!! }

            // Start from a known state, then fire the wired handler to pin manually.
            E2eFx.onFx {
                checkBox.isSelected = false
                checkBox.fire() // selects it AND runs setOnAction -> field enabled
            }
            E2eFx.onFx {
                assertTrue(checkBox.isSelected, "checkbox is now selected")
                assertFalse(field.isDisable, "pinning manually enables the gateway URL field")
            }

            // Firing again clears the pin and disables the field.
            E2eFx.onFx { checkBox.fire() }
            E2eFx.onFx {
                assertFalse(checkBox.isSelected, "checkbox cleared")
                assertTrue(field.isDisable, "clearing the override disables the gateway URL field")
            }
        } finally {
            E2eFx.onFx { view.onShellShutdown() }
            server.shutdown()
        }
    }

    @Test
    fun `firing the logout button invokes the callback and shows the signing-out feedback`() {
        val server = MockWebServer()
        server.dispatcher = okDispatcher()
        server.start()
        var loggedOut = false
        val view = buildView(server, onLogout = { loggedOut = true })
        try {
            val logoutButton = E2eFx.onFx { E2eFx.findAll<Button>(view).first { it.text == "Logout" } }

            E2eFx.onFx { logoutButton.fire() }

            E2eFx.onFx {
                assertTrue(loggedOut, "onLogout callback fired")
                assertTrue(logoutButton.isDisable, "logout button disables itself once clicked")
                assertTrue(E2eFx.hasText(view, "Signing out"), "signing-out feedback shown")
            }
        } finally {
            E2eFx.onFx { view.onShellShutdown() }
            server.shutdown()
        }
    }

    @Test
    fun `firing re-check services renders the endpoint probe rows and a completion feedback`() {
        val server = MockWebServer()
        server.dispatcher = okDispatcher()
        server.start()
        val view = buildView(server)
        try {
            E2eFx.onFx {
                E2eFx.findAll<Button>(view).first { it.text == "Re-check services" }.fire()
            }

            // The worker resolves the config + probes and re-renders the check list.
            E2eFx.waitForFx(timeoutMs = 25_000, description = "service checks rendered") {
                E2eFx.hasText(view, "Service check complete")
            }

            // Per-service rows carry the static probe names regardless of status.
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "API Gateway") }, "gateway probe row rendered")
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "Auth Context") }, "auth probe row rendered")
            // The 200 responses to the API probes surface as ONLINE in the meta line.
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "Online") }, "meta line reports online count")
            // The idle placeholder pill has been replaced by a check outcome.
            assertFalse(
                E2eFx.onFx { E2eFx.hasText(view, "Service checks have not run") },
                "the pre-check meta placeholder should be gone"
            )
        } finally {
            E2eFx.onFx { view.onShellShutdown() }
            server.shutdown()
        }
    }
}
