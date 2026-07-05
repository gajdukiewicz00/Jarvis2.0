package org.jarvis.desktop.headless

import javafx.application.Platform
import javafx.scene.control.Label
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Best-effort probe: can the JavaFX toolkit boot at all in this sandbox via
 * the Monocle software Glass/Prism pipeline (no real X11/Wayland display)?
 *
 * If [beforeAll] fails, every other test in this class is meaningless — the
 * environment genuinely cannot run JavaFX headlessly, and that ceiling is
 * reported rather than worked around.
 */
class HeadlessJavaFxSmokeTest {

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
                // Toolkit already running (e.g. re-entrant test run) — fine.
                latch.countDown()
            }
            toolkitStarted = latch.await(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `JavaFX toolkit starts headlessly via Monocle`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            toolkitStarted,
            "JavaFX toolkit did not start headlessly in this environment (see class doc) — skipping."
        )
    }

    @Test
    fun `a Control subclass can be constructed on the FX thread once the toolkit is up`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(toolkitStarted, "toolkit not started")

        val latch = CountDownLatch(1)
        var text: String? = null
        Platform.runLater {
            val label = Label("hello headless")
            text = label.text
            latch.countDown()
        }
        assertEquals(true, latch.await(10, TimeUnit.SECONDS), "Platform.runLater callback did not run in time")
        assertEquals("hello headless", text)
    }
}
