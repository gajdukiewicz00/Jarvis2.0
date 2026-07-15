package org.jarvis.desktop.e2e.media

import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.TextField
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.media.MediaJobsView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Additional headless-UI E2E journeys for [MediaJobsView] covering branches
 * not exercised by [MediaJobsViewE2eTest]:
 *
 *  - the synchronous Probe submission from the Create Job form (Start button)
 *    -> POST /media/probe -> probe-summary status line
 *  - operating-mode badge: REAL (no mock providers), MIXED, and the
 *    "Mode: unknown" fallback when GET /media/status fails
 *  - job cards for FAILED (with an error message) and CREATED statuses,
 *    exercising the status-tone + progress-node branches
 *
 * The Subtitles / Dub / Mux submissions and artifact download go through
 * MediaRawHttp (which targets the real AppConfig base URL, not this mock) and
 * the FileChooser save dialog, so they are intentionally not driven here.
 *
 * [MediaJobsView] is a ScrollPane; headlessly its skin never builds, so every
 * lookup roots at `view.content`.
 */
class MediaJobsViewMoreE2eTest {

    private fun contentOf(view: MediaJobsView): Node =
        E2eFx.onFx { requireNotNull(view.content) { "MediaJobsView content was not built" } }

    private fun json(body: String, code: Int = 200): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    /** A status payload whose provider modes are supplied by the caller. */
    private fun statusResponse(providers: String): MockResponse =
        json("""{"enabled": true, "jobStore": "memory", "providers": $providers}""")

    @Test
    fun `probe submission posts to media probe and renders a probe summary`() {
        val server = MockWebServer()
        // Probe uses ApiClient (sync POST /media/probe) — the only create path that hits this mock.
        server.enqueue(
            json("""{"video":[{}],"audio":[{},{}],"subtitle":[],"selectedAudioIndex":1,"durationSeconds":123.4}""")
        )
        server.start()
        try {
            val view = E2eFx.onFx { MediaJobsView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)

            // Fill the source path (job type defaults to PROBE) and fire Start.
            E2eFx.onFx {
                E2eFx.findAll<TextField>(root).first { it.promptText == "Media file path to probe" }.text = "/movies/clip.mkv"
                E2eFx.findAll<Button>(root).first { it.text == "Start" }.fire()
            }

            E2eFx.waitForFx(description = "probe summary rendered") {
                E2eFx.hasText(root, "Probe complete")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "1 video / 2 audio / 0 subtitle"), "stream counts summarised")
                assertTrue(E2eFx.hasText(root, "audio stream #1 auto-selected"), "selected audio index reported")
                assertTrue(E2eFx.hasText(root, "Ready"), "status pill flips to Ready after a probe")
            }

            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertTrue(req.path!!.contains("/media/probe"), "expected /media/probe, got ${req.path}")
            assertTrue(req.body.readUtf8().contains("/movies/clip.mkv"), "source path carried in body")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `blank source path is rejected by the form with no backend call`() {
        val server = MockWebServer()
        server.start() // no response enqueued — any request would prove a leak
        try {
            val view = E2eFx.onFx { MediaJobsView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)

            E2eFx.onFx {
                E2eFx.findAll<Button>(root).first { it.text == "Start" }.fire()
            }

            E2eFx.onFx {
                assertTrue(
                    E2eFx.hasText(root, "Enter a source path first"),
                    "blank source path warns in the form: ${E2eFx.visibleText(root)}"
                )
            }
            assertEquals(0, server.requestCount, "a blank source path must not hit the backend")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `mode badge reports REAL when no provider runs in mock`() {
        val server = MockWebServer()
        server.enqueue(json("[]"))
        server.enqueue(statusResponse("""{"ffprobe":"real","ffmpeg":"real","asr":"real","tts":"real"}"""))
        server.start()
        try {
            val view = E2eFx.onFx { MediaJobsView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "REAL mode badge rendered") {
                E2eFx.hasText(root, "Mode: REAL")
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `mode badge reports MIXED when providers disagree`() {
        val server = MockWebServer()
        server.enqueue(json("[]"))
        server.enqueue(statusResponse("""{"ffprobe":"real","ffmpeg":"mock","asr":"real","tts":"mock"}"""))
        server.start()
        try {
            val view = E2eFx.onFx { MediaJobsView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "MIXED mode badge rendered") {
                E2eFx.hasText(root, "Mode: MIXED")
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `mode badge falls back to unknown when the status probe fails`() {
        val server = MockWebServer()
        server.enqueue(json("[]"))                                   // jobs load OK
        server.enqueue(json("""{"error":"down"}""", code = 500))      // status probe 500
        server.start()
        try {
            val view = E2eFx.onFx { MediaJobsView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "unknown mode badge after status failure") {
                E2eFx.hasText(root, "Mode: unknown")
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `failed and created jobs render their status tones, progress and error message`() {
        val server = MockWebServer()
        server.enqueue(
            json(
                """
                [
                  {"id": "job-fail", "type": "RUSSIAN_SUBTITLES", "status": "FAILED",
                   "inputFile": "broken.json", "outputFiles": [], "errorMessage": "ffmpeg exploded"},
                  {"id": "job-new", "type": "MUX", "status": "CREATED",
                   "inputFile": "fresh.mkv", "outputFiles": []}
                ]
                """.trimIndent()
            )
        )
        server.enqueue(statusResponse("""{"ffprobe":"mock","ffmpeg":"mock","asr":"mock","tts":"mock"}"""))
        server.start()
        try {
            val view = E2eFx.onFx { MediaJobsView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "failed + created job cards rendered") {
                E2eFx.hasText(root, "RUSSIAN_SUBTITLES · job-fail") && E2eFx.hasText(root, "MUX · job-new")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "FAILED"), "failed status pill visible")
                assertTrue(E2eFx.hasText(root, "CREATED"), "created status pill visible")
                assertTrue(E2eFx.hasText(root, "ffmpeg exploded"), "error message surfaced on the failed card")
                assertTrue(E2eFx.hasText(root, "input: broken.json"), "input file surfaced")
                assertTrue(E2eFx.hasText(root, "Mode: MOCK"), "mock mode badge rendered")
            }
        } finally {
            server.shutdown()
        }
    }
}
