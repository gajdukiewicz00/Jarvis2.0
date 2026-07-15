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
 * THIRD-WAVE headless-UI E2E journeys for [MediaJobsView], targeting the
 * probe-summary formatting branches NOT covered by the earlier suites (which
 * probe a file whose response carries a duration and a selected audio index).
 *
 * Here the probe response omits both `durationSeconds` and `selectedAudioIndex`,
 * exercising the NULL sides of [MediaJobsView.formatProbeSummary]
 * ("unknown duration" / "no audio stream auto-selected"), and the optional
 * probe fields (preferred language + override audio index) are filled so the
 * form's non-null puts are carried in the request body.
 *
 * The Subtitles / Dub / Mux submissions and artifact download go through
 * MediaRawHttp / a native FileChooser (real AppConfig base URL), so they are
 * intentionally not driven. [MediaJobsView] is a ScrollPane; headlessly its
 * skin never builds, so every lookup roots at `view.content`.
 */
class MediaJobsViewThirdWaveE2eTest {

    private fun contentOf(view: MediaJobsView): Node =
        E2eFx.onFx { requireNotNull(view.content) { "MediaJobsView content was not built" } }

    private fun json(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    @Test
    fun `probe with no duration or selected audio renders the unknown fallbacks`() {
        val server = MockWebServer()
        // Probe response omits durationSeconds and selectedAudioIndex, and reports zero streams.
        server.enqueue(json("""{"video":[],"audio":[],"subtitle":[]}"""))
        server.start()
        try {
            val view = E2eFx.onFx { MediaJobsView(E2eFx.apiClientFor(server)) }
            val root = contentOf(view)

            E2eFx.onFx {
                E2eFx.findAll<TextField>(root).first { it.promptText == "Media file path to probe" }.text = "/movies/silent.mkv"
                E2eFx.findAll<TextField>(root)
                    .first { it.promptText == "Preferred language (optional, e.g. en)" }.text = "en"
                E2eFx.findAll<TextField>(root)
                    .first { it.promptText == "Override audio stream index (optional)" }.text = "2"
                E2eFx.findAll<Button>(root).first { it.text == "Start" }.fire()
            }

            E2eFx.waitForFx(description = "probe summary with unknown fallbacks rendered") {
                E2eFx.hasText(root, "Probe complete")
            }
            E2eFx.onFx {
                assertTrue(E2eFx.hasText(root, "unknown duration"), "null duration falls back to 'unknown duration'")
                assertTrue(
                    E2eFx.hasText(root, "no audio stream auto-selected"),
                    "null selected index falls back to 'no audio stream auto-selected'"
                )
                assertTrue(E2eFx.hasText(root, "0 video / 0 audio / 0 subtitle"), "zero-stream counts summarised")
                assertTrue(E2eFx.hasText(root, "Ready"), "status pill flips to Ready after a probe")
            }

            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertTrue(req.path!!.contains("/media/probe"), "expected /media/probe, got ${req.path}")
            val body = req.body.readUtf8()
            assertTrue(body.contains("/movies/silent.mkv"), "source path carried in body: $body")
            assertTrue(body.contains("preferredLanguage"), "preferred language carried in body: $body")
            assertTrue(body.contains("overrideAudioIndex"), "override audio index carried in body: $body")
        } finally {
            server.shutdown()
        }
    }
}
