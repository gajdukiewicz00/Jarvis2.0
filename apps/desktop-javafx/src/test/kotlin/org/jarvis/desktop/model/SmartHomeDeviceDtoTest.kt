package org.jarvis.desktop.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the default values and value-semantics of the smart-home DTOs, which
 * are otherwise only ever constructed fully-populated by the formatter tests.
 */
class SmartHomeDeviceDtoTest {

    @Test
    fun `SmartHomeDeviceDto defaults to empty scalars and collections`() {
        val device = SmartHomeDeviceDto()

        assertEquals("", device.id)
        assertEquals("", device.displayName)
        assertEquals("", device.room)
        assertEquals("", device.type)
        assertEquals("", device.provider)
        assertEquals("", device.updatedAt)
        assertTrue(device.supportedActions.isEmpty())
        assertTrue(device.state.isEmpty())
    }

    @Test
    fun `SmartHomeDeviceDto retains provided values and copy overrides one field`() {
        val device = SmartHomeDeviceDto(
            id = "d1",
            displayName = "Ceiling Light",
            room = "Living Room",
            type = "light",
            supportedActions = listOf("on", "off"),
            state = mapOf("power" to "on", "brightness" to 80),
            provider = "hue",
            updatedAt = "2026-07-08T10:00:00Z"
        )

        assertEquals(listOf("on", "off"), device.supportedActions)
        assertEquals("on", device.state["power"])

        val renamed = device.copy(displayName = "Lamp")
        assertEquals("Lamp", renamed.displayName)
        assertEquals("d1", renamed.id)
        assertEquals(device.state, renamed.state)
    }

    @Test
    fun `SmartHomeActionCommand payload defaults to null and equality is value based`() {
        val bare = SmartHomeActionCommand(action = "toggle")
        assertEquals("toggle", bare.action)
        assertNull(bare.payload)

        val withPayload = SmartHomeActionCommand(action = "set", payload = "{\"level\":50}")
        assertEquals("{\"level\":50}", withPayload.payload)

        assertEquals(SmartHomeActionCommand("toggle"), bare)
    }
}
