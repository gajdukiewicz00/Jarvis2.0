package org.jarvis.desktop.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pure-logic coverage for the remaining [PcControlWebSocketClient] companion
 * branches that never touch a websocket, a process, or the FX thread:
 * every [PcControlWebSocketClient.describeAction] arm (aliases, key fallbacks,
 * defaults, and the generic title-cased else branch), the error variant of
 * [PcControlWebSocketClient.formatStatusMessage], and the username-only
 * fallback of [PcControlWebSocketClient.buildIdentifyMessage].
 */
class PcControlWebSocketClientDescribeActionTest {

    private fun describe(action: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        PcControlWebSocketClient.describeAction(action, buildJsonObject(build))

    @Test
    @DisplayName("NOTIFICATION alias with a message renders title and body")
    fun notificationAliasWithMessage() {
        assertEquals(
            "Notification: Alarm - Wake up",
            describe("NOTIFICATION") {
                put("title", "Alarm")
                put("message", "Wake up")
            }
        )
    }

    @Test
    @DisplayName("NOTIFY with a blank message falls back to the title-only form")
    fun notificationWithBlankMessageUsesTitleOnly() {
        // Missing title defaults to "Jarvis"; blank message drops the body suffix.
        assertEquals("Notification: Jarvis", describe("NOTIFY") { put("message", "") })
    }

    @Test
    @DisplayName("SCENARIO reads the scenario key and defaults to unknown")
    fun scenarioKeyFallbacks() {
        assertEquals("Scenario: rest", describe("SCENARIO") { put("scenario", "rest") })
        assertEquals("Scenario: unknown", describe("SCENARIO"))
    }

    @Test
    @DisplayName("VOLUME_UP / VOLUME_DOWN honour delta and default to 10%")
    fun volumeDeltas() {
        assertEquals("Volume up 25%", describe("VOLUME_UP") { put("delta", 25) })
        assertEquals("Volume up 10%", describe("VOLUME_UP"))
        assertEquals("Volume down 5%", describe("VOLUME_DOWN") { put("delta", 5) })
        assertEquals("Volume down 10%", describe("VOLUME_DOWN"))
    }

    @Test
    @DisplayName("OPEN_APP prefers app, falls back to appName, then to a generic label")
    fun openAppFallbacks() {
        assertEquals("Open app: firefox", describe("OPEN_APP") { put("app", "firefox") })
        assertEquals("Open app: chrome", describe("OPEN_APP") { put("appName", "chrome") })
        assertEquals("Open app: application", describe("OPEN_APP"))
    }

    @Test
    @DisplayName("OPEN_URL reads url and defaults to a generic label")
    fun openUrlFallbacks() {
        assertEquals("Open url: https://example.com", describe("OPEN_URL") { put("url", "https://example.com") })
        assertEquals("Open url: url", describe("OPEN_URL"))
    }

    @Test
    @DisplayName("unknown actions are humanised via the generic else branch")
    fun genericElseBranch() {
        assertEquals("Lock screen", describe("LOCK_SCREEN"))
        // Lower-case input is upper-cased before matching, so aliases still resolve.
        assertEquals("Volume up 10%", describe("volume_up"))
    }

    @Test
    @DisplayName("formatStatusMessage appends the error suffix when one is present")
    fun formatStatusMessageWithError() {
        val params = buildJsonObject { put("app", "firefox") }
        assertEquals(
            "✗ Open app: firefox: boom",
            PcControlWebSocketClient.formatStatusMessage("✗", "OPEN_APP", params, "boom")
        )
        // A blank error string collapses back to the no-error form.
        assertEquals(
            "✓ Open app: firefox",
            PcControlWebSocketClient.formatStatusMessage("✓", "OPEN_APP", params, "")
        )
    }

    @Test
    @DisplayName("buildIdentifyMessage uses the username when no userId is present")
    fun identifyFallsBackToUsername() {
        val payload = Json.parseToJsonElement(
            PcControlWebSocketClient.buildIdentifyMessage(userId = null, username = "jarvis-user")
        ).jsonObject

        assertEquals("desktop-jarvis-user", payload["clientId"]?.toString()?.trim('"'))
        assertNull(payload["userId"])
        assertEquals("jarvis-user", payload["username"]?.toString()?.trim('"'))
        assertTrue(payload["capabilities"].toString().contains("SCENARIO"))
    }
}
