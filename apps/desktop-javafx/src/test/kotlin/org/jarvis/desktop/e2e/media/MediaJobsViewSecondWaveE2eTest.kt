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
 * SECOND-WAVE headless-UI E2E journeys for [MediaJobsView], targeting the
 * error/edge branches NOT covered by [MediaJobsViewE2eTest] /
 * [MediaJobsViewMoreE2eTest]:
 *
 *  - cancelJob backend rejection (POST cancel 500 -> "Unavailable" error tone)
 *  - a synchronous Probe submission that fails (POST probe 500 -> "Failed")
 *  - the mode badge UNKNOWN branch (providers map empty -> "Mode: UNKNOWN"),
 *    distinct from the exception fallback "Mode: unknown"
 *  - a job card with a BLANK inputFile, no errorMessage and no artifacts —
 *    exercising the false side of those three optional-render branches
 *
 * The Subtitles / Dub / Mux submissions and the artifact download go through
 * MediaRawHttp / a native FileChooser, so they are intentionally not driven.
 *
 * [MediaJobsView] is a ScrollPane; headlessly its skin never builds, so every
 * lookup roots at `view.content`.
 */
class MediaJobsViewSecondWaveE2eTest {

    private fun contentOf(view: MediaJobsView): Node =
        E2eFx.onFx { requireNotNull(view.content) { "MediaJobsView content was not built" } }

    private fun json(body: String, code: Int = 200): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun statusResponse(providers: String): MockResponse =
        json("""{"enabled": true, "jobStore": "memory", "providers": $providers}""")

    @Test
    fun `cancelling a job surfaces an error when the backend rejects the cancel`() {
        val server = MockWebServer()
        server.enqueue(
            json(
                """[{"id": "job-x", "type": "RUSSIAN_DUB_AUDIO", "status": "RUNNING",
                    "inputFile": "movie.ru.json", "outputFiles": []}]"""
            )
        )
        server.enqueue(statusResponse("""{"ffprobe":"mock","ffmpeg":"mock","asr":"mock","tts":"mock"}"""))
        // Cancel POST fails.
        server.enqueue(json("""{"error":"cannot cancel"}""", code = 500))
        server.start()
        try {
            val view = E2eFx.onFx { MediaJobsView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "running job card populated") {
                E2eFx.hasText(root, "RUSSIAN_DUB_AUDIO · job-x") && E2eFx.hasText(root, "RUNNING")
            }

            E2eFx.onFx {
                E2eFx.findAll<Button>(root).first { it.text == "Cancel" && !it.isDisable }.fire()
            }

            E2eFx.waitForFx(description = "cancel failure surfaces an Unavailable status") {
                E2eFx.hasText(root, "Unavailable")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "Server error (500)"), "mapped cancel error surfaced")
            }

            server.takeRequest() // list
            server.takeRequest() // status
            val cancel = server.takeRequest()
            assertEquals("POST", cancel.method)
            assertTrue(cancel.path!!.contains("/media/jobs/job-x/cancel"), "cancel path: ${cancel.path}")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a failing probe surfaces a Failed status`() {
        val server = MockWebServer()
        server.enqueue(json("""{"error":"ffprobe blew up"}""", code = 500))
        server.start()
        try {
            val view = E2eFx.onFx { MediaJobsView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)

            E2eFx.onFx {
                E2eFx.findAll<TextField>(root).first { it.promptText == "Media file path to probe" }.text = "/movies/bad.mkv"
                E2eFx.findAll<Button>(root).first { it.text == "Start" }.fire()
            }

            E2eFx.waitForFx(description = "probe failure surfaces a Failed status") {
                E2eFx.hasText(root, "Failed")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "Server error (500)"), "mapped probe error surfaced")
            }

            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertTrue(req.path!!.contains("/media/probe"), "probe path: ${req.path}")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `mode badge reports UNKNOWN when the provider set is empty`() {
        val server = MockWebServer()
        server.enqueue(json("[]"))
        server.enqueue(statusResponse("{}"))
        server.start()
        try {
            val view = E2eFx.onFx { MediaJobsView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "UNKNOWN mode badge rendered") {
                E2eFx.hasText(root, "Mode: UNKNOWN")
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a job with blank input, no error and no artifacts renders only its header`() {
        val server = MockWebServer()
        server.enqueue(
            json(
                """[{"id": "job-min", "type": "MUX", "status": "COMPLETED",
                    "inputFile": "", "outputFiles": []}]"""
            )
        )
        server.enqueue(statusResponse("""{"ffprobe":"mock","ffmpeg":"mock","asr":"mock","tts":"mock"}"""))
        server.start()
        try {
            val view = E2eFx.onFx { MediaJobsView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "minimal job card rendered") {
                E2eFx.hasText(root, "MUX · job-min")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "COMPLETED"), "completed status pill visible")
                // Blank inputFile -> the "input:" row is not rendered.
                assertTrue(!E2eFx.hasText(root, "input:"), "no input row for a blank inputFile")
                // No artifacts -> no Download button.
                assertTrue(
                    E2eFx.findAll<Button>(root).none { it.text == "Download" },
                    "no download control for a job with no artifacts"
                )
            }
        } finally {
            server.shutdown()
        }
    }
}
