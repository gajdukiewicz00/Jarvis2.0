package org.jarvis.desktop.headless

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.image.PixelFormat
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.features.agentswarm.AgentSwarmView
import org.jarvis.desktop.features.ai.AiView
import org.jarvis.desktop.features.analytics.AnalyticsView
import org.jarvis.desktop.features.controlcenter.ControlCenterView
import org.jarvis.desktop.features.finance.FinanceReviewView
import org.jarvis.desktop.features.insights.InsightsView
import org.jarvis.desktop.features.media.MediaJobsView
import org.jarvis.desktop.features.memory.MemoryView
import org.jarvis.desktop.features.pccontrol.PcControlView
import org.jarvis.desktop.features.planner.PlannerView
import org.jarvis.desktop.features.proactive.ProactiveView
import org.jarvis.desktop.features.security.SecuritySessionsView
import org.jarvis.desktop.features.settings.SettingsView
import org.jarvis.desktop.features.smarthome.SmartHomeView
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Renders each Control Center screen offscreen (Monocle software pipeline) and
 * writes a PNG per screen, so the actual laid-out UI (theme, panels, the new
 * app-wide scroll wrapper) can be reviewed without a physical display.
 *
 * Output dir: -Djarvis.snapshot.dir=<abs> (defaults to target/snapshots).
 * Not a behavioural test — it only proves the screens render to a real raster;
 * it is skipped gracefully if the headless toolkit can't start.
 */
@EnabledIfSystemProperty(
    named = "jarvis.snapshot.dir",
    matches = ".+",
    disabledReason = "Diagnostic-only: run explicitly with -Djarvis.snapshot.dir=<dir>; " +
        "kept out of the normal suite so its Stage lifecycle can't disturb the shared headless toolkit."
)
class ScreenSnapshotTest {

    companion object {
        private var toolkitStarted = false
        private const val W = 1200.0
        private const val H = 820.0

        @JvmStatic
        @BeforeAll
        fun startToolkit() {
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
                apiGatewayReason = "snapshot test",
                usesManualEndpointOverride = true
            )
        }
    )

    private val screens: List<Pair<String, () -> Node>> = listOf(
        "control-center" to { ControlCenterView(onNavigate = {}) },
        "memory" to { MemoryView(deadApiClient) },
        "finance-review" to { FinanceReviewView(deadApiClient) },
        "security-sessions" to { SecuritySessionsView(deadApiClient) },
        "smart-home" to { SmartHomeView(deadApiClient) },
        "agent-swarm" to { AgentSwarmView(deadApiClient) },
        "media-jobs" to { MediaJobsView(deadApiClient) },
        "planner" to { PlannerView(deadApiClient) },
        "analytics" to { AnalyticsView(deadApiClient) },
        "insights" to { InsightsView(deadApiClient) },
        "pc-control" to { PcControlView(deadApiClient) },
        "ai-runtime" to { AiView() },
        "proactive" to { ProactiveView(deadApiClient) },
        "settings" to { SettingsView(deadApiClient, onLogout = {}) }
    )

    @Test
    fun `render each screen to a PNG`() {
        assumeTrue(toolkitStarted, "JavaFX headless toolkit unavailable")

        val outDir = File(System.getProperty("jarvis.snapshot.dir", "target/snapshots")).apply { mkdirs() }
        val themeCss = ScreenSnapshotTest::class.java.getResource("/css/stark-lab.css")?.toExternalForm()

        val latch = CountDownLatch(1)
        val results = mutableListOf<String>()
        var failure: Throwable? = null

        Platform.runLater {
            try {
                val stage = Stage()
                for ((name, build) in screens) {
                    val root = StackPane(build()).apply {
                        styleClass += "shell-root"
                        padding = Insets(24.0)
                        prefWidth = W
                        prefHeight = H
                    }
                    val scene = Scene(root, W, H)
                    themeCss?.let { scene.stylesheets += it }
                    stage.scene = scene
                    stage.show()
                    root.applyCss()
                    root.layout()
                    val image = scene.snapshot(null)
                    val file = File(outDir, "$name.png")
                    writePng(image, file)
                    results += "$name -> ${file.length()} bytes (${image.width.toInt()}x${image.height.toInt()})"
                }
                stage.close()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }

        check(latch.await(60, TimeUnit.SECONDS)) { "snapshot render did not finish in time" }
        failure?.let { throw AssertionError("snapshot render threw on the FX thread", it) }

        results.forEach(::println)
        check(results.size == screens.size) { "expected ${screens.size} snapshots, wrote ${results.size}" }
        screens.forEach { (name, _) ->
            val f = File(outDir, "$name.png")
            check(f.exists() && f.length() > 0) { "snapshot $name.png was not written" }
        }
    }

    private fun writePng(image: javafx.scene.image.WritableImage, file: File) {
        val w = image.width.toInt()
        val h = image.height.toInt()
        val buffer = IntArray(w * h)
        image.pixelReader.getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), buffer, 0, w)
        val bufferedImage = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        bufferedImage.setRGB(0, 0, w, h, buffer, 0, w)
        ImageIO.write(bufferedImage, "png", file)
    }
}
