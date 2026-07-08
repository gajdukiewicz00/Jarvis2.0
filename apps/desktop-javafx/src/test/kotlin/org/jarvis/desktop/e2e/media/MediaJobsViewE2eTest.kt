package org.jarvis.desktop.e2e.media

import javafx.scene.Node
import javafx.scene.control.Button
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.media.MediaJobsView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Headless JavaFX end-to-end UI journeys for [MediaJobsView] (screen group: media).
 *
 * Each test constructs the REAL view on the FX thread pointed at a [MockWebServer]
 * standing in for the api-gateway, drives it through its public route-activation
 * entry point / real controls, then asserts BOTH that the visible scene graph
 * reacted (job cards, status pills, placeholders) AND that the backend received
 * the expected HTTP call(s).
 *
 * Endpoints exercised (see MediaJobsReadModel / MediaRawHttpTest):
 *   - list jobs   -> GET  /api/v1/media/jobs
 *   - mode badge  -> GET  /api/v1/media/status   (fired alongside on route activation)
 *   - cancel job  -> POST /api/v1/media/jobs/{id}/cancel  then a jobs re-list
 *
 * Note on artifact download: MediaJobsView constructs its read model with the
 * DEFAULT MediaRawHttp (which targets AppConfig.apiBaseUrl, not this mock server)
 * and then opens a native FileChooser save dialog. That byte-fetch + save cannot
 * run against the mock / headlessly, so the download journey here asserts the
 * download LINK renders for a completed job; the actual fetch+save is recorded in notes.
 *
 * Traversal note: [MediaJobsView] is a [javafx.scene.control.ScrollPane]. Headlessly
 * (no Scene / CSS pass) its skin never builds, so its `childrenUnmodifiable` — the
 * sequence [E2eFx.allNodes] walks — is empty and no rendered text is reachable from
 * the view node itself. Every scene-graph assertion therefore traverses from
 * `view.content` (the built VBox tree), exactly as the Finance/Brain E2E suites do.
 */
class MediaJobsViewE2eTest {

    /** The built content tree of the ScrollPane view — the traversable scene graph. */
    private fun contentOf(view: MediaJobsView): Node =
        E2eFx.onFx { requireNotNull(view.content) { "MediaJobsView content was not built" } }

    /** media-service mode/status probe fired by onRouteActivated() right after the jobs load. */
    private fun statusResponse(): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"enabled": true, "jobStore": "memory",
                "providers": {"ffprobe": "mock", "ffmpeg": "mock", "asr": "mock", "tts": "mock"}}"""
        )

    private fun jsonResponse(body: String, code: Int = 200): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    @Test
    fun `happy path - listing dub jobs renders a job card per job`() {
        val server = MockWebServer()
        // onRouteActivated fires: (1) GET /media/jobs then (2) GET /media/status — same single worker thread, in order.
        server.enqueue(
            jsonResponse(
                """
                [
                  {"id": "dub-1", "type": "RUSSIAN_DUB_AUDIO", "status": "RUNNING",
                   "inputFile": "movie.ru.json", "outputFiles": []},
                  {"id": "dub-2", "type": "RUSSIAN_DUB_AUDIO", "status": "COMPLETED",
                   "inputFile": "clip.ru.json",
                   "outputFiles": [{"kind": "dub-audio", "contentType": "audio/wav", "sizeBytes": 4096, "note": null}]}
                ]
                """.trimIndent()
            )
        )
        server.enqueue(statusResponse())
        server.start()
        try {
            val view = E2eFx.onFx { MediaJobsView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "dub job cards populated") {
                E2eFx.hasText(root, "RUSSIAN_DUB_AUDIO · dub-1") && E2eFx.hasText(root, "dub-2")
            }
            // Visible scene reacted: both jobs rendered, status pill flipped to Ready, count reported.
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "COMPLETED"), "completed job status pill visible")
                assertTrue(E2eFx.hasText(root, "RUNNING"), "running job status pill visible")
                assertTrue(E2eFx.hasText(root, "Ready"), "header status pill shows Ready")
                assertTrue(E2eFx.hasText(root, "2 job(s)"), "status label reports job count")
            }

            // Backend received the list call.
            val listReq = server.takeRequest()
            assertEquals("GET", listReq.method)
            assertTrue(listReq.path!!.contains("/media/jobs"), "expected /media/jobs, got ${listReq.path}")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `happy path - cancelling a running dub job flips its status to CANCELLED`() {
        val server = MockWebServer()
        // Route activation: jobs (running) + status.
        server.enqueue(
            jsonResponse(
                """
                [{"id": "dub-9", "type": "RUSSIAN_DUB_AUDIO", "status": "RUNNING",
                  "inputFile": "movie.ru.json", "outputFiles": []}]
                """.trimIndent()
            )
        )
        server.enqueue(statusResponse())
        // Cancel action fires: POST /media/jobs/dub-9/cancel then a jobs re-list (now CANCELLED).
        server.enqueue(jsonResponse("""{"cancelled": true, "status": "CANCELLED", "jobId": "dub-9"}"""))
        server.enqueue(
            jsonResponse(
                """
                [{"id": "dub-9", "type": "RUSSIAN_DUB_AUDIO", "status": "CANCELLED",
                  "inputFile": "movie.ru.json", "outputFiles": []}]
                """.trimIndent()
            )
        )
        server.start()
        try {
            val view = E2eFx.onFx { MediaJobsView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "running job card populated") {
                E2eFx.hasText(root, "RUSSIAN_DUB_AUDIO · dub-9") && E2eFx.hasText(root, "RUNNING")
            }

            // Fire the real, enabled Cancel button on the job card.
            E2eFx.onFx {
                val cancel = E2eFx.findAll<Button>(root).first { it.text == "Cancel" && !it.isDisable }
                cancel.fire()
            }

            // Visible scene reacted: the job's status pill flipped from RUNNING to CANCELLED.
            E2eFx.waitForFx(description = "job status flipped to CANCELLED") {
                E2eFx.hasText(root, "CANCELLED")
            }

            // Backend calls: list, status, cancel POST, re-list.
            val listReq = server.takeRequest()
            assertEquals("GET", listReq.method)
            assertTrue(listReq.path!!.contains("/media/jobs"))

            val statusReq = server.takeRequest()
            assertTrue(statusReq.path!!.contains("/media/status"))

            val cancelReq = server.takeRequest()
            assertEquals("POST", cancelReq.method)
            assertTrue(
                cancelReq.path!!.contains("/media/jobs/dub-9/cancel"),
                "expected cancel path, got ${cancelReq.path}"
            )

            val reListReq = server.takeRequest()
            assertEquals("GET", reListReq.method)
            assertTrue(reListReq.path!!.contains("/media/jobs"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `completed dub job renders an artifact download link`() {
        val server = MockWebServer()
        server.enqueue(
            jsonResponse(
                """
                [{"id": "dub-7", "type": "RUSSIAN_DUB_AUDIO", "status": "COMPLETED",
                  "inputFile": "movie.ru.json",
                  "outputFiles": [{"kind": "dub-audio", "contentType": "audio/wav", "sizeBytes": 4096, "note": null}]}]
                """.trimIndent()
            )
        )
        server.enqueue(statusResponse())
        server.start()
        try {
            val view = E2eFx.onFx { MediaJobsView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "completed job card populated") {
                E2eFx.hasText(root, "RUSSIAN_DUB_AUDIO · dub-7")
            }

            // The artifact row renders its descriptor label AND an enabled Download button.
            E2eFx.onFx {
                assertTrue(
                    E2eFx.hasText(root, "dub-audio (audio/wav, 4096 bytes)"),
                    "artifact descriptor label visible"
                )
                val download = E2eFx.findAll<Button>(root).firstOrNull { it.text == "Download" }
                assertTrue(download != null, "Download button present for the completed job's artifact")
                assertTrue(!download!!.isDisable, "Download button is enabled")
            }

            val listReq = server.takeRequest()
            assertTrue(listReq.path!!.contains("/media/jobs"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `error journey - a 500 on listing jobs surfaces the unavailable placeholder`() {
        val server = MockWebServer()
        server.enqueue(jsonResponse("""{"error": "boom"}""", code = 500))
        server.enqueue(statusResponse())
        server.start()
        try {
            val view = E2eFx.onFx { MediaJobsView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)
            E2eFx.onFx { view.onRouteActivated() }

            // Visible scene reacted: error placeholder + Unavailable status pill.
            E2eFx.waitForFx(description = "unavailable placeholder shown") {
                E2eFx.hasText(root, "Медиа-сервис временно недоступен") && E2eFx.hasText(root, "Unavailable")
            }

            val listReq = server.takeRequest()
            assertEquals("GET", listReq.method)
            assertTrue(listReq.path!!.contains("/media/jobs"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `edge journey - an empty jobs array shows the no-jobs placeholder`() {
        val server = MockWebServer()
        server.enqueue(jsonResponse("[]"))
        server.enqueue(statusResponse())
        server.start()
        try {
            val view = E2eFx.onFx { MediaJobsView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "empty-jobs placeholder shown") {
                E2eFx.hasText(root, "No media jobs yet")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "Ready"), "header status pill shows Ready")
                assertTrue(E2eFx.hasText(root, "0 job(s)"), "status label reports zero jobs")
            }

            val listReq = server.takeRequest()
            assertTrue(listReq.path!!.contains("/media/jobs"))
        } finally {
            server.shutdown()
        }
    }
}
