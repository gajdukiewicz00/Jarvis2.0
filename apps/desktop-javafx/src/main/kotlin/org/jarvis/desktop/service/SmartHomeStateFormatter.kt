package org.jarvis.desktop.service

import org.jarvis.desktop.model.SmartHomeDeviceDto
import kotlin.math.roundToInt

object SmartHomeStateFormatter {

    fun summarize(device: SmartHomeDeviceDto): String {
        return when (device.type.uppercase()) {
            "LIGHT" -> {
                val power = device.state["power"] as? Boolean ?: false
                val brightness = (device.state["brightness"] as? Number)?.toInt() ?: 0
                val color = device.state["color"]?.toString() ?: "unknown"
                if (power) "On • $brightness% • $color" else "Off • $brightness% • $color"
            }
            "THERMOSTAT" -> {
                val power = device.state["power"] as? Boolean ?: false
                val target = (device.state["targetTemperature"] as? Number)?.toDouble()?.let {
                    if (it % 1.0 == 0.0) "${it.roundToInt()}" else "$it"
                } ?: "?"
                val mode = device.state["mode"]?.toString() ?: "unknown"
                if (power) "Heating to ${target}°C • $mode" else "Off • target ${target}°C"
            }
            "LOCK" -> {
                val locked = device.state["locked"] as? Boolean ?: true
                if (locked) "Locked" else "Unlocked"
            }
            else -> device.state.entries.joinToString(", ") { "${it.key}=${it.value}" }
        }
    }
}
