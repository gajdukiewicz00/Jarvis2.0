package org.jarvis.desktop.features.analytics

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
 * Headless construction coverage for the chart-based [AnalyticsView], on the
 * Monocle software Glass/Prism pipeline (no display, no TestFX interaction —
 * only that the constructor + `init {}` that builds the chart widget tree
 * and loads the view-local stylesheet completes without throwing). Mirrors
 * the bootstrap used by the shared `org.jarvis.desktop.headless` smoke
 * tests, duplicated here so this feature package's test coverage does not
 * depend on editing a file outside `features/analytics`.
 */
class AnalyticsViewConstructionTest {

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

    private fun onFxThread(build: () -> Unit) {
        assumeTrue(toolkitStarted, "JavaFX did not start headlessly in this environment")
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        Platform.runLater {
            try {
                build()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "View construction did not complete on the FX thread in time" }
        failure?.let { throw AssertionError("View construction threw on the FX thread", it) }
    }

    @Test
    fun `AnalyticsView constructs its chart tree and shuts down cleanly`() {
        onFxThread {
            val view = AnalyticsView(deadApiClient)
            assertNotNull(view)
            view.onShellShutdown()
        }
    }
}
