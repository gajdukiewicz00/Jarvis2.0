package org.jarvis.desktop.features.vision

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class VisionSecurityReadModelTest {

    private fun configFor(server: MockWebServer): () -> ResolvedDesktopConfig {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        return {
            ResolvedDesktopConfig(
                apiGatewayBaseUrl = baseUrl,
                apiBaseUrl = "$baseUrl/api/v1",
                voiceWebSocketUrl = "$baseUrl/ws/voice",
                pcControlWebSocketUrl = "$baseUrl/ws/pc-control",
                locale = Locale.ENGLISH,
                voiceLanguage = "en-US",
                apiGatewaySource = ConfigSource.MANUAL_PERSISTED_SETTINGS,
                apiGatewayReason = "test",
                usesManualEndpointOverride = true
            )
        }
    }

    @Test
    fun `refresh parses full vision-security status payload into desktop snapshot`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "serviceStatus": "READY",
                      "monitoringEnabled": true,
                      "ownerEnrolled": true,
                      "activeUserId": "owner",
                      "lastDecision": "OWNER",
                      "lastReason": "Owner verified",
                      "lastFaceCount": 1,
                      "unknownStreak": 0,
                      "lastIncidentId": null,
                      "incidentCount": 0,
                      "camera": { "state": "AVAILABLE", "detail": "Camera backend V4L2" },
                      "screenshot": { "state": "AVAILABLE", "detail": "Using gnome-screenshot" },
                      "ocr": { "state": "AVAILABLE", "detail": "Using local tesseract CLI" },
                      "email": { "state": "UNAVAILABLE", "detail": "SMTP not configured" },
                      "gpu": { "preferGpu": false, "available": false, "activeBackend": "cpu", "detail": "CPU baseline" },
                      "config": {
                        "checkIntervalMs": 2000,
                        "debounceUnknownFrames": 3,
                        "alertCooldownSeconds": 60,
                        "storageRoot": "/home/jarvis/.jarvis/data/vision-security",
                        "emailRecipient": "",
                        "ocrLanguage": "eng",
                        "preferGpu": false,
                        "displayServer": "x11"
                      }
                    }
                    """.trimIndent()
                )
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("[]")
        )

        try {
            server.start()
            val readModel = VisionSecurityReadModel(ApiClient(configFor(server)))

            val snapshot = readModel.refresh()

            assertNotNull(snapshot.refreshedAt)
            val status = snapshot.status
            assertEquals("READY", status.serviceStatus)
            assertTrue(status.monitoringEnabled)
            assertTrue(status.ownerEnrolled)
            assertEquals("owner", status.activeUserId)
            assertEquals("OWNER", status.lastDecision)
            assertEquals("Owner verified", status.lastReason)
            assertEquals(1, status.lastFaceCount)

            assertEquals("AVAILABLE", status.camera.state)
            assertTrue(status.camera.detail.contains("V4L2"))
            assertEquals("AVAILABLE", status.screenshot.state)
            assertTrue(status.screenshot.detail.contains("gnome-screenshot"))
            assertEquals("AVAILABLE", status.ocr.state)
            assertTrue(status.ocr.detail.contains("tesseract"))
            assertEquals("UNAVAILABLE", status.email.state)

            assertEquals("cpu", status.gpu.activeBackend)
            assertFalse(status.gpu.available)

            assertEquals(2000L, status.config.checkIntervalMs)
            assertEquals("eng", status.config.ocrLanguage)
            assertEquals("x11", status.config.displayServer)

            assertEquals(0, snapshot.incidents.size)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `refresh applies safe defaults when fields are missing or null`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "serviceStatus": "DEGRADED" }""")
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("[]")
        )

        try {
            server.start()
            val readModel = VisionSecurityReadModel(ApiClient(configFor(server)))

            val snapshot = readModel.refresh()
            val status = snapshot.status

            assertEquals("DEGRADED", status.serviceStatus)
            assertFalse(status.monitoringEnabled)
            assertFalse(status.ownerEnrolled)
            assertEquals("UNKNOWN", status.camera.state)
            assertEquals("UNKNOWN", status.screenshot.state)
            assertEquals("UNKNOWN", status.ocr.state)
            assertEquals("cpu", status.gpu.activeBackend)
            assertEquals("eng", status.config.ocrLanguage)
            assertEquals("unknown", status.config.displayServer)
            assertEquals("No vision security activity yet", status.lastReason)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `refresh maps OCR degraded path into desktop OCR capability snapshot`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "serviceStatus": "DEGRADED",
                      "ocr": { "state": "UNAVAILABLE", "detail": "Install tesseract-ocr to enable OCR extraction" },
                      "camera": { "state": "AVAILABLE", "detail": "Camera backend V4L2" },
                      "screenshot": { "state": "AVAILABLE", "detail": "Using scrot" }
                    }
                    """.trimIndent()
                )
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("[]")
        )

        try {
            server.start()
            val readModel = VisionSecurityReadModel(ApiClient(configFor(server)))

            val status = readModel.refresh().status

            assertEquals("DEGRADED", status.serviceStatus)
            assertEquals("UNAVAILABLE", status.ocr.state)
            assertTrue(status.ocr.detail.contains("tesseract-ocr"))
            assertEquals("AVAILABLE", status.camera.state)
            assertEquals("AVAILABLE", status.screenshot.state)
        } finally {
            server.shutdown()
        }
    }
}
