package org.jarvis.desktop.features.media

import javafx.application.Platform
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Dedicated, feature-local headless construction check for [MediaJobsView].
 *
 * This only verifies that the constructor + `init {}` (which now also wires
 * [MediaJobCreateForm] and loads the view-local `media-jobs.css` resource)
 * completes without throwing on the Monocle software Glass/Prism pipeline —
 * no TestFX interaction, no CSS/layout assertions. A missing/misnamed CSS
 * resource would surface here as a thrown `IllegalArgumentException` from
 * `requireNotNull`, since construction runs the whole `init {}` block.
 */
class MediaJobsViewConstructionTest {

    companion object {
        private var toolkitStarted = false

        @JvmStatic
        @BeforeAll
        fun startToolkit() {
            System.setProperty("testfx.robot", "glass")
            System.setProperty("testfx.headless", "true")
            System.setProperty("prism.order", "sw")
            System.setProperty("prism.text", "t2k")
            System.setProperty("glass.platform", "Monocle")
            System.setProperty("monocle.platform", "Headless")
            System.setProperty("java.awt.headless", "true")

            val latch = CountDownLatch(1)
            try {
                Platform.startup { latch.countDown() }
            } catch (alreadyStarted: IllegalStateException) {
                latch.countDown()
            }
            toolkitStarted = latch.await(10, TimeUnit.SECONDS)
        }
    }

    /** No server is started — this URL simply has nothing listening on it. */
    private val deadApiClient = ApiClient(
        configProvider = {
            ResolvedDesktopConfig(
                apiGatewayBaseUrl = "http://127.0.0.1:1",
                apiBaseUrl = "http://127.0.0.1:1/api/v1",
                voiceWebSocketUrl = "ws://127.0.0.1:1/ws/voice",
                pcControlWebSocketUrl = "ws://127.0.0.1:1/ws/pc-control",
                locale = Locale.ENGLISH,
                voiceLanguage = "en-US",
                apiGatewaySource = ConfigSource.MANUAL_PERSISTED_SETTINGS,
                apiGatewayReason = "headless smoke test",
                usesManualEndpointOverride = true
            )
        }
    )

    @Test
    fun `MediaJobsView constructs without throwing, including its create-job form and stylesheet`() {
        assumeTrue(toolkitStarted, "JavaFX did not start headlessly in this environment — see HeadlessJavaFxSmokeTest")

        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        var view: MediaJobsView? = null
        Platform.runLater {
            try {
                view = MediaJobsView(deadApiClient)
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "View construction did not complete on the FX thread in time" }
        failure?.let { throw AssertionError("MediaJobsView construction threw on the FX thread", it) }
        assertNotNull(view)
        assertNotNull(view!!.content)
    }
}
