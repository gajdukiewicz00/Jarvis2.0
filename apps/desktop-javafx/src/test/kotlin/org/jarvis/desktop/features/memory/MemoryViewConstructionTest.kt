package org.jarvis.desktop.features.memory

import javafx.application.Platform
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Headless (Monocle) coverage for [MemoryView]'s construction — verifies the
 * constructor + `init {}` (widget tree + the new filter/bulk-action/export-import
 * controls added on top of search/pin/scope/why/forget) builds without throwing.
 * No TestFX interaction (clicking, layout, CSS) — matches the scope of
 * `org.jarvis.desktop.headless.HeadlessViewConstructionSmokeTest`, but kept as a
 * standalone file in this package so it doesn't need edits to that shared test.
 *
 * The fake [ApiClient] points at a base URL where nothing is listening, so
 * construction (which does no network I/O) always completes fast and never
 * reaches a real backend or the k3s cluster.
 */
class MemoryViewConstructionTest {

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
                apiGatewayReason = "memory view headless test",
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
    fun `MemoryView constructs without throwing`() {
        onFxThread {
            assertNotNull(MemoryView(deadApiClient))
        }
    }

    @Test
    fun `two independent MemoryView instances construct without throwing`() {
        onFxThread {
            assertNotNull(MemoryView(deadApiClient))
            assertNotNull(MemoryView(deadApiClient))
        }
    }

    @Test
    fun `repeated construction does not throw (no static state corrupts later instances)`() {
        onFxThread {
            repeat(3) {
                assertNotNull(MemoryView(deadApiClient))
            }
        }
    }

    @Test
    fun `MemoryReadModel exposes the import conflict modes used by the view`() {
        assertEquals(listOf("skip", "overwrite", "keep-both"), MemoryReadModel.IMPORT_CONFLICT_MODES)
    }
}
