package org.jarvis.desktop.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PcControlWebSocketClientTest {

    @Test
    fun `buildIdentifyMessage includes authenticated user context`() {
        val payload = Json.parseToJsonElement(
            PcControlWebSocketClient.buildIdentifyMessage("user-42", "jarvis-user")
        ).jsonObject

        assertEquals("IDENTIFY", payload["type"]?.toString()?.trim('"'))
        assertEquals("desktop-user-42", payload["clientId"]?.toString()?.trim('"'))
        assertEquals("user-42", payload["userId"]?.toString()?.trim('"'))
        assertEquals("jarvis-user", payload["username"]?.toString()?.trim('"'))
        assertTrue(payload["capabilities"].toString().contains("VOLUME_CONTROL"))
    }

    @Test
    fun `buildIdentifyMessage falls back to anonymous client id when user context is absent`() {
        val payload = Json.parseToJsonElement(
            PcControlWebSocketClient.buildIdentifyMessage(null, null)
        ).jsonObject

        assertEquals("desktop-anonymous", payload["clientId"]?.toString()?.trim('"'))
        assertNull(payload["userId"])
        assertNull(payload["username"])
    }

    @Test
    fun `describeAction includes notification and scenario details`() {
        val notification = buildJsonObject {
            put("title", "Reminder")
            put("message", "Stand up")
        }
        val scenario = buildJsonObject {
            put("name", "focus")
        }

        assertEquals(
            "Notification: Reminder - Stand up",
            PcControlWebSocketClient.describeAction("NOTIFY", notification)
        )
        assertEquals(
            "Scenario: focus",
            PcControlWebSocketClient.describeAction("SCENARIO", scenario)
        )
        assertTrue(
            PcControlWebSocketClient.formatStatusMessage("✓", "NOTIFY", notification)
                .contains("Reminder - Stand up")
        )
    }
}
