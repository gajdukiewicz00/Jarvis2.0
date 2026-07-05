package org.jarvis.desktop.headless

import javafx.application.Platform
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.features.agentswarm.AgentSwarmView
import org.jarvis.desktop.features.ai.AiView
import org.jarvis.desktop.features.analytics.AnalyticsView
import org.jarvis.desktop.features.brain.BrainChatView
import org.jarvis.desktop.features.controlcenter.ControlCenterView
import org.jarvis.desktop.features.diagnostics.DiagnosticsView
import org.jarvis.desktop.features.finance.FinanceReviewView
import org.jarvis.desktop.features.finance.FinanceView
import org.jarvis.desktop.features.home.HomeView
import org.jarvis.desktop.features.insights.InsightsView
import org.jarvis.desktop.features.media.MediaJobsView
import org.jarvis.desktop.features.memory.MemoryView
import org.jarvis.desktop.features.pccontrol.PcControlView
import org.jarvis.desktop.features.planner.PlannerView
import org.jarvis.desktop.features.proactive.ProactiveView
import org.jarvis.desktop.features.security.SecuritySessionsView
import org.jarvis.desktop.features.security.SecurityView
import org.jarvis.desktop.features.settings.SettingsView
import org.jarvis.desktop.features.smarthome.ScenesView
import org.jarvis.desktop.features.smarthome.SmartHomeView
import org.jarvis.desktop.features.sync.SyncPairingView
import org.jarvis.desktop.features.vision.VisionSecurityView
import org.jarvis.desktop.features.voice.VoiceHelpView
import org.jarvis.desktop.runtime.DesktopRuntimeMonitor
import org.jarvis.desktop.shell.ShellNavPane
import org.jarvis.desktop.shell.ShellNavigator
import org.jarvis.desktop.shell.ShellTopBar
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Best-effort headless coverage for the JavaFX *View layer, which is
 * otherwise ~12k of the module's ~15k lines and entirely uncovered.
 *
 * This does NOT attempt full TestFX interaction (clicking, layout, CSS) —
 * only that each View's constructor + `init {}` (the part that builds the
 * static widget tree and wires button handlers) completes without throwing,
 * using the Monocle software Glass/Prism pipeline started once for the whole
 * class. Every View instantiated here was verified beforehand to do no
 * network I/O and no hardware access (audio/webcam/Robot) during
 * construction — refresh/action logic only runs from `onRouteActivated()` or
 * a button handler, neither of which this test invokes. The fake
 * [ApiClient] below points at a base URL where nothing is listening, so even
 * if that assumption is ever violated by a future edit, calls fail fast
 * with a connection error instead of reaching a real backend or the k3s
 * cluster.
 */
class HeadlessViewConstructionSmokeTest {

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

    @Test
    fun `apiClient-only views construct without throwing`() {
        onFxThread {
            assertNotNull(MemoryView(deadApiClient))
            assertNotNull(FinanceView(deadApiClient))
            assertNotNull(ScenesView(deadApiClient))
            assertNotNull(SmartHomeView(deadApiClient))
            assertNotNull(PlannerView(deadApiClient))
            assertNotNull(AnalyticsView(deadApiClient))
            assertNotNull(DiagnosticsView(deadApiClient))
            assertNotNull(VisionSecurityView(deadApiClient))
            assertNotNull(PcControlView(deadApiClient))
            assertNotNull(InsightsView(deadApiClient))
            assertNotNull(ProactiveView(deadApiClient))
            assertNotNull(SecurityView(deadApiClient))
            assertNotNull(SyncPairingView(deadApiClient))
            assertNotNull(BrainChatView(deadApiClient))
            assertNotNull(SecuritySessionsView(deadApiClient))
            assertNotNull(AgentSwarmView(deadApiClient))
            assertNotNull(MediaJobsView(deadApiClient))
            assertNotNull(FinanceReviewView(deadApiClient))
        }
    }

    @Test
    fun `views with a small extra callback construct without throwing`() {
        onFxThread {
            assertNotNull(VoiceHelpView(deadApiClient, onOpenVoiceControl = {}))
            assertNotNull(SettingsView(deadApiClient, onLogout = {}))
            assertNotNull(ControlCenterView(onNavigate = {}))
        }
    }

    @Test
    fun `AiView constructs with its default read model`() {
        onFxThread {
            assertNotNull(AiView())
        }
    }

    @Test
    fun `HomeView constructs with a fresh runtime monitor and no-op callbacks`() {
        onFxThread {
            assertNotNull(
                HomeView(
                    runtimeMonitor = DesktopRuntimeMonitor(),
                    onRefreshRuntime = {},
                    onOpenPlanner = {},
                    onOpenLife = {},
                    onOpenAnalytics = {},
                    onOpenPcControl = {},
                    onOpenSmartHome = {},
                    onOpenVision = {},
                    onOpenVoice = {},
                    onOpenDiagnostics = {},
                    onOpenSettings = {}
                )
            )
        }
    }

    @Test
    fun `shell chrome components construct from a plain ShellNavigator`() {
        onFxThread {
            val navigator = ShellNavigator()
            assertNotNull(ShellNavPane(navigator))
            assertNotNull(ShellTopBar(navigator))
        }
    }
}
