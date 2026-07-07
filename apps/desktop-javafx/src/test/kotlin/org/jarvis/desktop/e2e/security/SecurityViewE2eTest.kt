package org.jarvis.desktop.e2e.security

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
 * Endpoints (see SecurityReadModel):
 *   status  -> GET  /api/v1/security/auth/privacy
 *   enable  -> POST /api/v1/security/auth/privacy/on
 *   disable -> POST /api/v1/security/auth/privacy/off
 */
class SecurityViewE2eTest {

    @BeforeEach
    fun requireToolkit() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable headlessly — skipping")
    }

    private fun buttonByText(root: javafx.scene.Node, label: String): Button =
        E2eFx.findAll<Button>(root).first { it.text == label }

    @Test
    fun `privacy status loads on route activation then enable toggles it ON`() {
        val server = MockWebServer()
        // 1) initial status load (route activation)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"enabled": false, "detail": "Privacy mode is OFF."}""")
        )
        server.start()
        try {
            val view = E2eFx.onFx { SecurityView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "initial OFF status rendered") {
                E2eFx.hasText(view, "Privacy mode is OFF")
            }

            val statusReq = server.takeRequest()
            assertEquals("GET", statusReq.method)
            assertEquals("/api/v1/security/auth/privacy", statusReq.path)

            // Pill should read OFF after the initial load.
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "OFF") }, "status pill should read OFF")

            // 2) user clicks "Enable privacy" -> POST .../privacy/on
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"enabled": true, "detail": "Privacy mode is ON."}""")
            )
            E2eFx.onFx { buttonByText(view, "Enable privacy").fire() }

            E2eFx.waitForFx(description = "privacy flipped ON in UI") {
                E2eFx.hasText(view, "Privacy mode is ON")
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
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"enabled": false, "detail": "Privacy mode is OFF."}""")
        )
        server.start()
        try {
            val view = E2eFx.onFx { SecurityView(E2eFx.apiClientFor(server)) }
            // Fire disable directly (no route activation needed).
            E2eFx.onFx { buttonByText(view, "Disable privacy").fire() }

            E2eFx.waitForFx(description = "privacy rendered OFF") {
                E2eFx.hasText(view, "Privacy mode is OFF")
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
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "unavailable state surfaced") {
                E2eFx.hasText(view, "Unavailable") || E2eFx.hasText(view, "Privacy controls")
            }

            // The pill flips to the error tone text.
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "Unavailable") }, "pill should read Unavailable")

            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertEquals("/api/v1/security/auth/privacy", req.path)
        } finally {
            server.shutdown()
        }
    }
}
