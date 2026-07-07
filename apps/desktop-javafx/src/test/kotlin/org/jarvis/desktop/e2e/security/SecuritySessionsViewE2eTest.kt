package org.jarvis.desktop.e2e.security

import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.TextField
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.security.SecuritySessionsView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * True headless UI E2E for the Security Sessions & Audit panel
 * ([SecuritySessionsView]).
 *
 * Drives the REAL scene graph (route activation to load the audit trail,
 * revoke-token and revoke-all controls) against a MockWebServer, then asserts
 * BOTH that the visible audit rows / action-result text updated AND that the
 * expected HTTP request(s) reached the backend.
 *
 * Endpoints (see SecuritySessionsReadModel):
 *   audit trail       -> GET  /api/v1/security/auth/audit?limit=50
 *   revoke a token    -> POST /api/v1/security/auth/revoke
 *   revoke all (user) -> POST /api/v1/security/auth/revoke-all/{userId}
 *
 * NOTE: "Revoke my current session" is confirm-gated behind a modal
 * Dialog.showAndWait(); see the manifest notes for why that path is not
 * headlessly drivable under Monocle.
 */
class SecuritySessionsViewE2eTest {

    @BeforeEach
    fun requireToolkit() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable headlessly — skipping")
    }

    private fun buttonByText(root: Node, label: String): Button =
        E2eFx.findAll<Button>(root).first { it.text == label }

    private fun fieldByPrompt(root: Node, prompt: String): TextField =
        E2eFx.findAll<TextField>(root).first { it.promptText == prompt }

    @Test
    fun `audit trail loads on route activation and renders events`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {"eventType": "TOKEN_ISSUED", "userId": "owner-1",
                       "tokenReference": "jti-aaa", "occurredAt": "2026-07-06T10:00:00Z",
                       "reason": "login"},
                      {"eventType": "TOKEN_REVOKED", "userId": "user-9",
                       "tokenReference": "jti-bbb", "occurredAt": "2026-07-06T11:00:00Z",
                       "reason": null}
                    ]
                    """.trimIndent()
                )
        )
        server.start()
        try {
            val view = E2eFx.onFx { SecuritySessionsView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "audit rows rendered") {
                E2eFx.hasText(view, "TOKEN_ISSUED") && E2eFx.hasText(view, "TOKEN_REVOKED")
            }

            // The row meta line surfaces the user and timestamp.
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "owner-1") }, "should render userId meta")
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "2026-07-06T11:00:00Z") }, "should render occurredAt")
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "Ready") }, "pill should read Ready")

            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertEquals("/api/v1/security/auth/audit?limit=50", req.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `empty audit payload renders the no-events placeholder`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody("[]")
        )
        server.start()
        try {
            val view = E2eFx.onFx { SecuritySessionsView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "empty placeholder rendered") {
                E2eFx.hasText(view, "No audit events recorded yet.")
            }

            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertEquals("/api/v1/security/auth/audit?limit=50", req.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `403 on audit surfaces Forbidden pill and OWNER-role guidance`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"forbidden"}"""))
        server.start()
        try {
            val view = E2eFx.onFx { SecuritySessionsView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "forbidden guidance rendered") {
                E2eFx.hasText(view, "Requires OWNER role")
            }
            assertTrue(E2eFx.onFx { E2eFx.hasText(view, "Forbidden") }, "pill should read Forbidden")

            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertEquals("/api/v1/security/auth/audit?limit=50", req.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `revoke token posts the token then reloads the audit trail`() {
        val server = MockWebServer()
        // POST /revoke response, then the follow-up GET audit response.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"revoked": true, "jti": "jti-9", "tokenType": "access"}""")
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """[{"eventType":"TOKEN_REVOKED","userId":"user-9",
                        "tokenReference":"jti-9","occurredAt":"2026-07-06T12:00:00Z",
                        "reason":"manual revoke"}]""".trimIndent()
                )
        )
        server.start()
        try {
            val view = E2eFx.onFx { SecuritySessionsView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx {
                fieldByPrompt(view, "Access or refresh token to revoke").text = "tok-123"
                buttonByText(view, "Revoke token").fire()
            }

            E2eFx.waitForFx(description = "revoke result + reloaded audit rendered") {
                E2eFx.hasText(view, "Revoked access (jti=jti-9).") && E2eFx.hasText(view, "TOKEN_REVOKED")
            }
            // Field is cleared after a successful revoke.
            assertTrue(
                E2eFx.onFx { fieldByPrompt(view, "Access or refresh token to revoke").text.isEmpty() },
                "token field should be cleared"
            )

            val revokeReq = server.takeRequest()
            assertEquals("POST", revokeReq.method)
            assertEquals("/api/v1/security/auth/revoke", revokeReq.path)
            assertTrue(revokeReq.body.readUtf8().contains("tok-123"), "body should carry the token")

            val auditReq = server.takeRequest()
            assertEquals("GET", auditReq.method)
            assertEquals("/api/v1/security/auth/audit?limit=50", auditReq.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `revoke token with a blank field is guarded and sends no request`() {
        val server = MockWebServer()
        server.start()
        try {
            val view = E2eFx.onFx { SecuritySessionsView(E2eFx.apiClientFor(server)) }
            // Do not type a token; fire revoke.
            E2eFx.onFx { buttonByText(view, "Revoke token").fire() }

            E2eFx.waitForFx(description = "blank-token guard message shown") {
                E2eFx.hasText(view, "Enter a token to revoke first.")
            }

            assertEquals(0, server.requestCount, "no HTTP call should be made for a blank token")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `revoke all sessions for a user posts to revoke-all then reloads audit`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"userId": "user-7", "revokedRefreshTokens": 3}""")
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody("[]")
        )
        server.start()
        try {
            val view = E2eFx.onFx { SecuritySessionsView(E2eFx.apiClientFor(server)) }
            E2eFx.onFx {
                fieldByPrompt(view, "User id").text = "user-7"
                buttonByText(view, "Revoke all sessions").fire()
            }

            E2eFx.waitForFx(description = "revoke-all result rendered") {
                E2eFx.hasText(view, "Revoked 3 refresh token(s) for user user-7.")
            }
            assertTrue(
                E2eFx.onFx { fieldByPrompt(view, "User id").text.isEmpty() },
                "user id field should be cleared"
            )

            val revokeAllReq = server.takeRequest()
            assertEquals("POST", revokeAllReq.method)
            assertEquals("/api/v1/security/auth/revoke-all/user-7", revokeAllReq.path)

            val auditReq = server.takeRequest()
            assertEquals("GET", auditReq.method)
            assertEquals("/api/v1/security/auth/audit?limit=50", auditReq.path)
        } finally {
            server.shutdown()
        }
    }
}
