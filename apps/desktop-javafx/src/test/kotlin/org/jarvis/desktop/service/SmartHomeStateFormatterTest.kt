package org.jarvis.desktop.service

import org.jarvis.desktop.model.SmartHomeDeviceDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmartHomeStateFormatterTest {

    @Test
    fun `summarize renders light state clearly`() {
        val device = SmartHomeDeviceDto(
            id = "kitchen_light",
            displayName = "Kitchen Light",
            room = "Kitchen",
            type = "LIGHT",
            state = mapOf("power" to true, "brightness" to 70, "color" to "warm_white")
        )

        assertEquals("On • 70% • warm_white", SmartHomeStateFormatter.summarize(device))
    }

    @Test
    fun `summarize renders thermostat state clearly`() {
        val device = SmartHomeDeviceDto(
            id = "hall_thermostat",
            displayName = "Hall Thermostat",
            room = "Hall",
            type = "THERMOSTAT",
            state = mapOf("power" to true, "targetTemperature" to 22.0, "mode" to "HEAT")
        )

        assertEquals("Heating to 22°C • HEAT", SmartHomeStateFormatter.summarize(device))
    }
}
