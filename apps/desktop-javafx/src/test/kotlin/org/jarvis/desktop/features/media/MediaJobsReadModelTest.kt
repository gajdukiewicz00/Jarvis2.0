package org.jarvis.desktop.features.media

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class MediaJobsReadModelTest {

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

    private fun modelFor(server: MockWebServer) = MediaJobsReadModel(ApiClient(configFor(server)))

    @Test
    fun `listJobs parses jobs including artifacts`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {
                        "id": "job-1", "type": "RUSSIAN_SUBTITLES", "status": "COMPLETED",
                        "inputFile": "movie.mp4",
                        "outputFiles": [{"kind": "subtitle-srt", "path": "/work/job-1/out.srt", "contentType": "text/srt", "sizeBytes": 1024, "note": null}],
                        "createdAt": "2026-01-01T00:00:00Z", "updatedAt": "2026-01-01T00:05:00Z", "errorMessage": null
                      },
                      {
                        "id": "job-2", "type": "TRANSCRIBE", "status": "FAILED",
                        "inputFile": "clip.mp4", "outputFiles": [], "errorMessage": "ffmpeg not found"
                      }
                    ]
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val jobs = modelFor(server).listJobs()

            assertEquals(2, jobs.size)
            assertEquals("job-1", jobs[0].id)
            assertEquals("COMPLETED", jobs[0].status)
            assertTrue(jobs[0].isTerminal)
            assertEquals(1, jobs[0].artifacts.size)
            assertEquals("subtitle-srt", jobs[0].artifacts[0].kind)
            assertEquals(1024L, jobs[0].artifacts[0].sizeBytes)

            assertEquals("ffmpeg not found", jobs[1].errorMessage)
            assertTrue(jobs[1].isTerminal)
            assertTrue(jobs[1].artifacts.isEmpty())

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("/media/jobs"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `listJobs returns empty list on a non-array payload`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error": "unexpected"}""")
        )

        try {
            server.start()
            assertTrue(modelFor(server).listJobs().isEmpty())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `cancel posts to the cancel endpoint and reports whether it was cancelled`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"cancelled": true, "status": "CANCELLED", "jobId": "job-1"}""")
        )

        try {
            server.start()
            val cancelled = modelFor(server).cancel("job-1")

            assertTrue(cancelled)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/media/jobs/job-1/cancel"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `cancel reports false when the backend declines`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"cancelled": false, "status": "COMPLETED", "jobId": "job-1"}""")
        )

        try {
            server.start()
            assertFalse(modelFor(server).cancel("job-1"))
        } finally {
            server.shutdown()
        }
    }
}
