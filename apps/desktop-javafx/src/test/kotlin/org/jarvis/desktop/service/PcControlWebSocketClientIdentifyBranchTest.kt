package org.jarvis.desktop.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pure-logic coverage for the remaining [PcControlWebSocketClient] companion
 * branches not reached by [PcControlWebSocketClientDescribeActionTest]:
 *  - [PcControlWebSocketClient.buildIdentifyMessage] preferring the userId
 *    (`desktop-<userId>`) and its both-null "desktop-anonymous" fallback.
 *  - The SCENARIO `name` key of [PcControlWebSocketClient.describeAction]
 *    (the sibling only covers the `scenario` alias and the default).
 *
 * These never touch a websocket, a process, or the FX thread.
 */
class PcControlWebSocketClientIdentifyBranchTest {

    private fun identify(userId: String?, username: String?) =
        Json.parseToJsonElement(
            PcControlWebSocketClient.buildIdentifyMessage(userId, username)
        ).jsonObject

    @Test
    @DisplayName("buildIdentifyMessage prefers the userId for the clientId")
    fun identifyPrefersUserId() {
        val payload = identify(userId = "u-42", username = "ignored-when-userid-present")

        assertEquals("desktop-u-42", payload["clientId"]?.toString()?.trim('"'))
        assertEquals("u-42", payload["userId"]?.toString()?.trim('"'))
        assertEquals("ignored-when-userid-present", payload["username"]?.toString()?.trim('"'))
    }

    @Test
    @DisplayName("buildIdentifyMessage falls back to desktop-anonymous when nothing is known")
    fun identifyAnonymousFallback() {
        val payload = identify(userId = null, username = null)

        assertEquals("desktop-anonymous", payload["clientId"]?.toString()?.trim('"'))
        assertNull(payload["userId"], "no userId field when userId is null")
        assertNull(payload["username"], "no username field when username is null")
    }

    @Test
    @DisplayName("buildIdentifyMessage treats a blank userId as absent and uses the username")
    fun identifyBlankUserIdFallsThroughToUsername() {
        // Blank userId is `isNullOrBlank()` -> the username arm wins.
        val payload = identify(userId = "   ", username = "operator")

        assertEquals("desktop-operator", payload["clientId"]?.toString()?.trim('"'))
        assertNull(payload["userId"], "blank userId must not be emitted")
        assertEquals("operator", payload["username"]?.toString()?.trim('"'))
    }

    @Test
    @DisplayName("describeAction reads the SCENARIO name key")
    fun describeScenarioNameKey() {
        assertEquals(
            "Scenario: morning",
            PcControlWebSocketClient.describeAction("SCENARIO", buildJsonObject { put("name", "morning") })
        )
    }
}
