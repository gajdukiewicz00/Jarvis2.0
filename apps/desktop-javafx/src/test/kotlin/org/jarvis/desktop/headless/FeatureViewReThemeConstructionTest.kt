package org.jarvis.desktop.headless

import javafx.application.Platform
import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.features.analytics.AnalyticsView
import org.jarvis.desktop.features.insights.InsightsView
import org.jarvis.desktop.features.life.LifeMapView
import org.jarvis.desktop.features.life.LifeView
import org.jarvis.desktop.features.pccontrol.PcControlView
import org.jarvis.desktop.features.smarthome.SmartHomeView
import org.jarvis.desktop.lifemap.LifeMapClient
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Headless construction smoke coverage for the six Stark-Lab re-themed
 * feature screens (Life, Life Map, Analytics, Insights, Smart Home, PC
 * Control). Mirrors [HeadlessViewConstructionSmokeTest]: this only verifies
 * that each View's constructor + `init {}` (the part that builds the
 * re-themed widget tree) completes without throwing on the Monocle
 * software Glass/Prism pipeline — no TestFX interaction, no CSS/layout
 * pixel assertions (this sandbox has no display to render against).
 *
 * [LifeMapView] additionally schedules a background refresh as part of
 * `LifeMapPanel.build()` (`startPolling()` with an initial delay of zero),
 * so it is built here with a [LifeMapClient] pointed at the same dead
 * loopback address as [deadApiClient] — every call fails fast instead of
 * reaching a real backend or the k3s cluster — and shut down immediately
 * after construction to cancel that scheduler before the test returns.
 */
class FeatureViewReThemeConstructionTest {

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
        assumeTrue(toolkitStarted, "JavaFX did not start headlessly in this environment — see HeadlessJavaFxSmokeTest")
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

    @Suppress("DEPRECATION")
    @Test
    fun `re-themed legacy-tab-hosting views construct without throwing`() {
        onFxThread {
            assertNotNull(LifeView(deadApiClient))
            assertNotNull(AnalyticsView(deadApiClient))
            assertNotNull(PcControlView(deadApiClient))
            assertNotNull(SmartHomeView(deadApiClient))
            assertNotNull(InsightsView(deadApiClient))
        }
    }

    @Test
    fun `LifeMapView constructs without throwing and shuts down its refresh scheduler`() {
        onFxThread {
            val view = LifeMapView(
                apiClient = deadApiClient,
                liveFeed = AgentLiveFeed(),
                lifeMapClient = LifeMapClient("http://127.0.0.1:1")
            )
            assertNotNull(view)
            view.onShellShutdown()
        }
    }
}
