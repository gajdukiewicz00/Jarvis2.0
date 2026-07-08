package org.jarvis.desktop.e2e.security

import javafx.scene.Node
import javafx.scene.control.Button
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.security.SecurityView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * True headless UI E2E for the Security / Privacy panel ([SecurityView]).
 *
 * Drives the REAL scene graph (route activation, toggle buttons) against a
 * MockWebServer standing in for the gateway, then asserts BOTH the visible
 * labels/pill reacted AND the expected HTTP call reached the backend.
 *
 * NOTE: [SecurityView] is a [javafx.scene.control.ScrollPane]. Its skin (and
 * therefore its `childrenUnmodifiable`) is only built once the control is
 * attached to a live [javafx.scene.Scene] and laid out — which never happens
 * in a headless Monocle test. We therefore walk the REAL content subtree the
 * view builds (`view.content`, the VBox holding the header + status card),
 * which contains every control the user interacts with (pill, headline,
 * detail, Enable/Disable/Refresh buttons).
 *
 * Endpoints (see SecurityReadModel + ApiClient's `/api/v1` prefix):
 *   status  -> GET  /api/v1/security/auth/privacy
 *   enable  -> POST /api/v1/security/auth/privacy/on
 *   disable -> POST /api/v1/security/auth/privacy/off
 */
class SecurityViewE2eTest {

    @BeforeEach
    fun requireToolkit() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable headlessly — skipping")
    }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /** The real content subtree the ScrollPane wraps — reachable without a live Scene. */
    private fun contentRoot(view: SecurityView): Node =
        requireNotNull(view.content) { "SecurityView content was not built" }

    private fun buttonByText(root: Node, label: String): Button =
        E2eFx.findAll<Button>(root).first { it.text == label }

    @Test
    fun `privacy status loads on route activation then enable toggles it ON`() {
        val server = MockWebServer()
        // 1) initial status load (route activation)
        server.enqueue(jsonResponse("""{"enabled": false, "detail": "Privacy mode is OFF."}"""))
        server.start()
        try {
            val view = E2eFx.onFx { SecurityView(E2eFx.apiClientFor(server)) }
            val root = E2eFx.onFx { contentRoot(view) }
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "initial OFF status rendered") {
                E2eFx.hasText(root, "Privacy mode is OFF")
            }

            val statusReq = server.takeRequest()
            assertEquals("GET", statusReq.method)
            assertEquals("/api/v1/security/auth/privacy", statusReq.path)

            // Pill should read OFF after the initial load.
            assertTrue(E2eFx.onFx { E2eFx.hasText(root, "OFF") }, "status pill should read OFF")

            // 2) user clicks "Enable privacy" -> POST .../privacy/on
            server.enqueue(jsonResponse("""{"enabled": true, "detail": "Privacy mode is ON."}"""))
            E2eFx.onFx { buttonByText(root, "Enable privacy").fire() }

            E2eFx.waitForFx(description = "privacy flipped ON in UI") {
                E2eFx.hasText(root, "Privacy mode is ON")
            }

            val enableReq = server.takeRequest()
            assertEquals("POST", enableReq.method)
            assertEquals("/api/v1/security/auth/privacy/on", enableReq.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `disable button posts to privacy off and renders OFF`() {
        val server = MockWebServer()
        server.enqueue(jsonResponse("""{"enabled": false, "detail": "Privacy mode is OFF."}"""))
        server.start()
        try {
            val view = E2eFx.onFx { SecurityView(E2eFx.apiClientFor(server)) }
            val root = E2eFx.onFx { contentRoot(view) }
            // Fire disable directly (no route activation needed).
            E2eFx.onFx { buttonByText(root, "Disable privacy").fire() }

            E2eFx.waitForFx(description = "privacy rendered OFF") {
                E2eFx.hasText(root, "Privacy mode is OFF")
            }

            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/api/v1/security/auth/privacy/off", req.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `backend 500 on status surfaces an unavailable state in the UI`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.start()
        try {
            val view = E2eFx.onFx { SecurityView(E2eFx.apiClientFor(server)) }
            val root = E2eFx.onFx { contentRoot(view) }
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "unavailable state surfaced") {
                E2eFx.hasText(root, "Unavailable") || E2eFx.hasText(root, "Privacy controls")
            }

            // The pill flips to the error tone text.
            assertTrue(E2eFx.onFx { E2eFx.hasText(root, "Unavailable") }, "pill should read Unavailable")

            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertEquals("/api/v1/security/auth/privacy", req.path)
        } finally {
            server.shutdown()
        }
    }
}
