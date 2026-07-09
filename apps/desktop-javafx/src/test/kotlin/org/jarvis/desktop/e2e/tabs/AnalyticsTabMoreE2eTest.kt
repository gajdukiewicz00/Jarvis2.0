package org.jarvis.desktop.e2e.tabs

import javafx.scene.Node
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.ui.tabs.AnalyticsTab
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Second-wave headless coverage for [AnalyticsTab] branches the primary suite
 * skips: the per-pane "server unavailable" arm (reached when the HTTP layer
 * raises a connection-refused error, distinct from the 5xx "Error:" arm) and
 * the `formatTimeData` empty-payload placeholder (a different method from the
 * already-covered `formatExpenseData` empty branch).
 */
class AnalyticsTabMoreE2eTest {

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /** An [ApiClient] pointed at a port where nothing listens — every probe fails with a refused connection. */
    private fun deadApiClient(): ApiClient = ApiClient(
        configProvider = {
            ResolvedDesktopConfig(
                apiGatewayBaseUrl = "http://127.0.0.1:1",
                apiBaseUrl = "http://127.0.0.1:1/api/v1",
                voiceWebSocketUrl = "ws://127.0.0.1:1/ws/voice",
                pcControlWebSocketUrl = "ws://127.0.0.1:1/ws/pc-control",
                locale = Locale.ENGLISH,
                voiceLanguage = "en-US",
                apiGatewaySource = ConfigSource.MANUAL_PERSISTED_SETTINGS,
                apiGatewayReason = "analytics unavailable test",
                usesManualEndpointOverride = true
            )
        }
    )

    @Test
    fun `a refused connection marks every pane unavailable and fails the banner`() {
        val content: Node = E2eFx.onFx { AnalyticsTab(deadApiClient()).tab.content }

        // Each of the four loaders hits ConnectException -> "Connection refused ..."
        // which maps to the per-pane "Server unavailable" guidance, and all four
        // land in the failure list so the status banner reports 4 failed panes.
        E2eFx.waitForFx(description = "all four analytics panes failed") {
            E2eFx.hasText(content, "Analytics failed in 4 pane(s)")
        }
        assertTrue(
            E2eFx.onFx { E2eFx.hasText(content, "Server unavailable") },
            "each pane should render the connection-refused guidance"
        )
    }

    @Test
    fun `empty time payload renders the no-time-data placeholder`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("/analytics/time/summary") -> json("[]")
                    path.contains("/analytics/expenses/by-month") ->
                        json("""[{"period":"Jan","totalAmount":1200.50,"currency":"$","count":10}]""")
                    path.contains("/analytics/expenses/by-category") ->
                        json("""[{"category":"Groceries","totalAmount":500.00,"currency":"$","count":5}]""")
                    path.contains("/analytics/calendar/summary") ->
                        json("""{"totalEvents":42,"upcomingEvents":5,"pastEvents":37,"allDayEvents":2}""")
                    else -> json("[]")
                }
            }
        }
        server.start()
        try {
            val content: Node = E2eFx.onFx { AnalyticsTab(E2eFx.apiClientFor(server)).tab.content }

            E2eFx.waitForFx(description = "no time-tracking placeholder") {
                E2eFx.hasText(content, "No time tracking data available")
            }
            assertTrue(
                E2eFx.onFx { E2eFx.hasText(content, "Analytics loaded successfully") },
                "overall status stays success when only the time pane is empty"
            )
        } finally {
            server.shutdown()
        }
    }
}
