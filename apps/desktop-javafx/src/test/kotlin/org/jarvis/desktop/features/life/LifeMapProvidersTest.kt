package org.jarvis.desktop.features.life

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.agent.command.DesktopActions
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.features.planner.PlannerReadModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.Locale

class LifeMapProvidersTest {

    private fun configFor(server: MockWebServer): () -> ResolvedDesktopConfig {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        return {
            ResolvedDesktopConfig(
                apiGatewayBaseUrl = baseUrl,
                apiBaseUrl = "$baseUrl/api/v1",
                voiceWebSocketUrl = "$baseUrl/ws/voice",
                pcControlWebSocketUrl = "$baseUrl/ws/pc-control",
                locale = Locale.ENGLISH,
                voiceLanguage = "en-US",
                apiGatewaySource = ConfigSource.MANUAL_PERSISTED_SETTINGS,
                apiGatewayReason = "test",
                usesManualEndpointOverride = true
            )
        }
    }

    @Test
    fun `loadTasks maps planner-service response into TasksSnapshot`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {"id":1,"title":"Ship Life Map","priority":"HIGH","status":"TODO","tags":[]},
                      {"id":2,"title":"Update README","priority":"MEDIUM","status":"IN_PROGRESS","tags":[]},
                      {"id":3,"title":"Old chore","priority":"LOW","status":"DONE","tags":[]}
                    ]
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val apiClient = ApiClient(configFor(server))
            val providers = LifeMapProviders(
                plannerReadModel = PlannerReadModel(apiClient),
                desktopActions = null,
                apiClient = apiClient
            )

            val snapshot = providers.loadTasks()

            assertNotNull(snapshot)
            assertEquals(2, snapshot!!.open)
            assertEquals(1, snapshot.doneToday)
            assertTrue(snapshot.recent.any { it.contains("Ship Life Map") })
            assertTrue(snapshot.recent.any { it.contains("Update README") })
            assertFalse(snapshot.recent.any { it.contains("Old chore") })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadTasks returns null when planner-service is unreachable so the panel falls back`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))

        try {
            server.start()
            val apiClient = ApiClient(configFor(server))
            val providers = LifeMapProviders(
                plannerReadModel = PlannerReadModel(apiClient),
                desktopActions = null,
                apiClient = apiClient
            )

            assertNull(providers.loadTasks())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadActivity reports DEGRADED when no desktop-agent binding is wired`() {
        val server = MockWebServer()
        try {
            server.start()
            val apiClient = ApiClient(configFor(server))
            val providers = LifeMapProviders(
                plannerReadModel = PlannerReadModel(apiClient),
                desktopActions = null,
                apiClient = apiClient
            )

            val snapshot = providers.loadActivity()

            assertNull(snapshot.activeWindowTitle)
            assertNotNull(snapshot.degradedReason)
            assertTrue(snapshot.degradedReason!!.contains("desktop-agent"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadActivity surfaces the active window title from the desktop-agent probe`() {
        val server = MockWebServer()
        try {
            server.start()
            val apiClient = ApiClient(configFor(server))
            val stubActions = StubActions(
                result = DesktopActions.ActionResult.ok(mapOf("title" to "Firefox – jarvis.local"))
            )
            val providers = LifeMapProviders(
                plannerReadModel = PlannerReadModel(apiClient),
                desktopActions = stubActions,
                apiClient = apiClient
            )

            val snapshot = providers.loadActivity()

            assertEquals("Firefox – jarvis.local", snapshot.activeWindowTitle)
            assertNull(snapshot.degradedReason)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadActivity records the degraded reason when the active-window probe fails`() {
        val server = MockWebServer()
        try {
            server.start()
            val apiClient = ApiClient(configFor(server))
            val stubActions = StubActions(
                result = DesktopActions.ActionResult.fail("xdotool exit=1")
            )
            val providers = LifeMapProviders(
                plannerReadModel = PlannerReadModel(apiClient),
                desktopActions = stubActions,
                apiClient = apiClient
            )

            val snapshot = providers.loadActivity()

            assertNull(snapshot.activeWindowTitle)
            assertEquals("xdotool exit=1", snapshot.degradedReason)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadInsights renders memory-service highlights when the response is healthy`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {"text":"Owner mentioned migrating CV"},
                      {"text":"Pending PR on Life route"}
                    ]
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val apiClient = ApiClient(configFor(server))
            val providers = LifeMapProviders(
                plannerReadModel = PlannerReadModel(apiClient),
                desktopActions = null,
                apiClient = apiClient
            )

            val snapshot = providers.loadInsights()

            assertTrue(snapshot.available)
            assertEquals(2, snapshot.items.size)
            assertTrue(snapshot.items.any { it.contains("migrating CV") })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadInsights is degraded when memory-service returns non-success`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))

        try {
            server.start()
            val apiClient = ApiClient(configFor(server))
            val providers = LifeMapProviders(
                plannerReadModel = PlannerReadModel(apiClient),
                desktopActions = null,
                apiClient = apiClient
            )

            val snapshot = providers.loadInsights()

            assertFalse(snapshot.available)
            assertNotNull(snapshot.reason)
        } finally {
            server.shutdown()
        }
    }

    private class StubActions(
        private val result: DesktopActions.ActionResult
    ) : DesktopActions {
        override fun openApp(app: String, args: List<String>): DesktopActions.ActionResult =
            DesktopActions.ActionResult.fail("not used")

        override fun focusWindow(titleSubstring: String): DesktopActions.ActionResult =
            DesktopActions.ActionResult.fail("not used")

        override fun typeText(text: String, perCharDelayMs: Long): DesktopActions.ActionResult =
            DesktopActions.ActionResult.fail("not used")

        override fun openUrl(url: String): DesktopActions.ActionResult =
            DesktopActions.ActionResult.fail("not used")

        override fun createLocalNote(title: String, body: String, directory: Path?): DesktopActions.ActionResult =
            DesktopActions.ActionResult.fail("not used")

        override fun showNotification(summary: String, body: String, urgency: String): DesktopActions.ActionResult =
            DesktopActions.ActionResult.fail("not used")

        override fun getActiveWindow(): DesktopActions.ActionResult = result
    }
}
