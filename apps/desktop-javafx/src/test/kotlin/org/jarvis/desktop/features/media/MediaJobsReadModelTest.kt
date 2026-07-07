package org.jarvis.desktop.features.media

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class MediaJobsReadModelTest {

    private fun apiBaseUrlFor(server: MockWebServer): String =
        server.url("/").toString().removeSuffix("/") + "/api/v1"

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

    private fun modelFor(server: MockWebServer) = MediaJobsReadModel(
        apiClient = ApiClient(configFor(server)),
        rawHttp = MediaRawHttp { apiBaseUrlFor(server) }
    )

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

    @Test
    fun `status reports MOCK when every provider mode is mock`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"enabled": true, "jobStore": "memory",
                     "providers": {"ffprobe": "mock", "ffmpeg": "mock", "asr": "mock", "translation": "mock", "tts": "mock"}}
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val status = modelFor(server).status()

            assertTrue(status.enabled)
            assertEquals("memory", status.jobStore)
            assertEquals("MOCK", status.overallMode)

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("/media/status"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `status reports REAL when no provider mode is mock and MIXED otherwise`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"enabled": true, "jobStore": "postgres", "providers": {"ffprobe": "native", "ffmpeg": "native"}}"""
                )
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"enabled": true, "jobStore": "postgres", "providers": {"ffprobe": "native", "ffmpeg": "mock"}}"""
                )
        )

        try {
            server.start()
            val model = modelFor(server)
            assertEquals("REAL", model.status().overallMode)
            assertEquals("MIXED", model.status().overallMode)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `probe parses stream counts and the selected audio index without creating a job`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"video": [{}], "audio": [{}, {}], "subtitle": [], "selectedAudioIndex": 1, "durationSeconds": 120.5}
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val summary = modelFor(server).probe("movie.mp4", "en", null)

            assertEquals(1, summary.videoStreams)
            assertEquals(2, summary.audioStreams)
            assertEquals(0, summary.subtitleStreams)
            assertEquals(1, summary.selectedAudioIndex)
            assertEquals(120.5, summary.durationSeconds)

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("/media/probe"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"inputFile\":\"movie.mp4\""))
            assertTrue(body.contains("\"preferredLanguage\":\"en\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `createSubtitlesJob accepts a 202 Accepted response and parses the created job`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"id": "job-9", "type": "RUSSIAN_SUBTITLES", "status": "CREATED", "inputFile": "transcript.json",
                     "outputFiles": []}
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val job = modelFor(server).createSubtitlesJob("transcript.json")

            assertEquals("job-9", job.id)
            assertEquals("CREATED", job.status)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/media/jobs/russian-subtitles"))
            assertTrue(request.body.readUtf8().contains("\"transcriptFile\":\"transcript.json\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `createDubJob sends the voice profile fields and parses the created job`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id": "job-10", "type": "RUSSIAN_DUB_AUDIO", "status": "CREATED", "inputFile": "transcript.ru.json"}"""
                )
        )

        try {
            server.start()
            val job = modelFor(server).createDubJob(
                "transcript.ru.json",
                voiceProfileMode = "user_owned",
                voiceId = "voice-1",
                consentConfirmed = true
            )

            assertEquals("job-10", job.id)

            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"transcriptFile\":\"transcript.ru.json\""))
            assertTrue(body.contains("\"voiceProfileMode\":\"user_owned\""))
            assertTrue(body.contains("\"voiceId\":\"voice-1\""))
            assertTrue(body.contains("\"consentConfirmed\":true"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `createMuxJob sends only the non-blank optional fields`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": "job-11", "type": "MUX", "status": "CREATED", "inputFile": "movie.mp4"}""")
        )

        try {
            server.start()
            val job = modelFor(server).createMuxJob(
                originalFile = "movie.mp4",
                subtitleFile = "sub.srt",
                dubAudioFile = null,
                outputName = null
            )

            assertEquals("job-11", job.id)

            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"originalFile\":\"movie.mp4\""))
            assertTrue(body.contains("\"subtitleFile\":\"sub.srt\""))
            assertFalse(body.contains("dubAudioFile"))
            assertFalse(body.contains("outputName"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `downloadArtifact returns the exact raw bytes from the artifact endpoint`() {
        val server = MockWebServer()
        val binary = okio.Buffer().write(byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0x7F, 0x80.toByte()))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(binary)
        )

        try {
            server.start()
            val bytes = modelFor(server).downloadArtifact("job-1", 0)

            assertArrayEquals(byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0x7F, 0x80.toByte()), bytes)
            assertTrue(server.takeRequest().path!!.contains("/media/jobs/job-1/artifacts/0"))
        } finally {
            server.shutdown()
        }
    }
}
