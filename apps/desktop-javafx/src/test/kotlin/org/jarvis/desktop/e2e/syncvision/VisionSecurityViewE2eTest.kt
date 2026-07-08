package org.jarvis.desktop.e2e.syncvision

import javafx.scene.Scene
import javafx.scene.control.Button
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.vision.VisionSecurityView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * True UI end-to-end journeys for [VisionSecurityView].
 *
 * The view refreshes by issuing two GETs (status + incidents) on route
 * activation and after every action. Each test constructs the REAL view
 * against a [MockWebServer], drives a real control, and asserts BOTH the
 * backend calls AND the rendered scene graph (status pill, headline, metric
 * cards, incident cards, feedback pill).
 */
class VisionSecurityViewE2eTest {

    private fun statusBody(
        serviceStatus: String = "READY",
        monitoringEnabled: Boolean = false,
        ownerEnrolled: Boolean = true,
        lastReason: String = "Owner recognized at desk"
    ): String = """
        {
          "serviceStatus": "$serviceStatus",
          "monitoringEnabled": $monitoringEnabled,
          "ownerEnrolled": $ownerEnrolled,
          "activeUserId": "owner-1",
          "lastDecision": "OWNER",
          "lastReason": "$lastReason",
          "lastFaceCount": 1,
          "unknownStreak": 0,
          "lastIncidentId": null,
          "incidentCount": 1,
          "camera": {"state": "AVAILABLE", "detail": "Creative Live Cam Sync 4K"},
          "screenshot": {"state": "AVAILABLE", "detail": "X11 grab ready"},
          "ocr": {"state": "AVAILABLE", "detail": "tesseract eng"},
          "email": {"state": "DEGRADED", "detail": "SMTP not configured"},
          "gpu": {"preferGpu": false, "available": false, "activeBackend": "cpu", "detail": "CPU baseline"},
          "config": {
            "checkIntervalMs": 2000,
            "debounceUnknownFrames": 3,
            "alertCooldownSeconds": 60,
            "storageRoot": "/var/jarvis/vision",
            "emailRecipient": "owner@example.com",
            "ocrLanguage": "eng",
            "preferGpu": false,
            "displayServer": "x11"
          }
        }
    """.trimIndent()

    private val incidentsBody = """
        [
          {
            "incidentId": "inc-42",
            "createdAt": "2026-07-06T10:00:00Z",
            "decision": "UNKNOWN",
            "reason": "Unknown face lingered at the desk",
            "semanticTags": ["unknown", "desk"],
            "screenContext": {"activeWindowTitle": "Terminal", "activeProcessName": "bash"},
            "incidentDirectory": "/var/jarvis/vision/inc-42",
            "webcamPhotoPath": "/var/jarvis/vision/inc-42/webcam.jpg",
            "screenshotPath": "/var/jarvis/vision/inc-42/screen.png"
          }
        ]
    """.trimIndent()

    private fun jsonResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    /**
     * Build the real view on the FX thread and force skin/layout so the
     * [javafx.scene.control.ScrollPane] content (header, cards, incident list)
     * is mounted into the scene graph and therefore reachable via [E2eFx.hasText].
     * Without a Scene + applyCss + layout the ScrollPane skin is never created and
     * its content subtree stays invisible to the traversal helpers.
     */
    private fun buildView(server: MockWebServer): VisionSecurityView = E2eFx.onFx {
        val view = VisionSecurityView(E2eFx.apiClientFor(server))
        Scene(view)
        view.applyCss()
        view.layout()
        view
    }

    @Test
    fun `status snapshot renders on route activation`() {
        val server = MockWebServer()
        server.enqueue(jsonResponse(statusBody()))
        server.enqueue(jsonResponse(incidentsBody))
        server.start()
        try {
            val view = buildView(server)
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "status snapshot rendered") {
                E2eFx.hasText(view, "Monitoring is paused") &&
                    E2eFx.hasText(view, "Unknown face lingered at the desk")
            }

            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "READY"), "service pill should read READY")
                assertTrue(E2eFx.hasText(view, "Owner recognized at desk"), "detail should show last reason")
                assertTrue(E2eFx.hasText(view, "Enrolled"), "owner metric should read Enrolled")
                assertTrue(E2eFx.hasText(view, "/var/jarvis/vision"), "config should show the storage root")
                // Incident card rendered from the incidents endpoint.
                assertTrue(E2eFx.hasText(view, "inc-42"), "incident evidence path should render")
                assertTrue(E2eFx.hasText(view, "Terminal"), "incident window title should render")
            }

            // Both refresh GETs reached the backend.
            val paths = listOf(server.takeRequest(), server.takeRequest()).map { it.method to it.path!! }
            assertTrue(paths.any { it.first == "GET" && it.second.contains("/vision-security/status") }, "paths=$paths")
            assertTrue(
                paths.any { it.first == "GET" && it.second.contains("/vision-security/incidents") },
                "paths=$paths"
            )

            E2eFx.onFx { view.onShellShutdown() }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `start monitoring action posts and renders feedback`() {
        val server = MockWebServer()
        // Initial route-activation refresh (monitoring paused so Start is enabled).
        server.enqueue(jsonResponse(statusBody(monitoringEnabled = false)))
        server.enqueue(jsonResponse(incidentsBody))
        // Action POST response.
        server.enqueue(jsonResponse("""{"lastReason":"Vision security monitoring is running"}"""))
        // Post-action refresh (monitoring now enabled).
        server.enqueue(jsonResponse(statusBody(monitoringEnabled = true, lastReason = "Monitoring loop engaged")))
        server.enqueue(jsonResponse(incidentsBody))
        server.start()
        try {
            val view = buildView(server)
            E2eFx.onFx { view.onRouteActivated() }

            // Wait until the initial snapshot enables the Start Monitoring button.
            E2eFx.waitForFx(description = "start button enabled after first snapshot") {
                E2eFx.findAll<Button>(view).firstOrNull { it.text == "Start Monitoring" }?.isDisable == false
            }

            E2eFx.onFx {
                E2eFx.findAll<Button>(view).first { it.text == "Start Monitoring" }.fire()
            }

            E2eFx.waitForFx(description = "monitoring-started feedback rendered") {
                E2eFx.hasText(view, "Monitoring started")
            }

            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Monitoring started"), "feedback pill should confirm the action")
            }

            // Drain the two initial GETs, then assert the action POST hit the backend.
            server.takeRequest()
            server.takeRequest()
            val post = server.takeRequest()
            assertEquals("POST", post.method)
            assertTrue(post.path!!.contains("/vision-security/monitoring/start"), "path was ${post.path}")

            E2eFx.onFx { view.onShellShutdown() }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `refresh failure renders unavailable state`() {
        val server = MockWebServer()
        // Status GET fails; refresh() throws before the incidents GET.
        server.enqueue(MockResponse().setResponseCode(500).setBody("backend unhealthy"))
        server.start()
        try {
            val view = buildView(server)
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "failure state rendered") {
                E2eFx.hasText(view, "Vision security refresh failed")
            }

            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Vision security refresh failed"), "updated label should show failure")
                assertTrue(E2eFx.hasText(view, "Unavailable"), "feedback pill should read Unavailable")
            }

            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertTrue(req.path!!.contains("/vision-security/status"), "path was ${req.path}")

            E2eFx.onFx { view.onShellShutdown() }
        } finally {
            server.shutdown()
        }
    }
}
