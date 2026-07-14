package org.jarvis.desktop.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for the only non-hardware part of input-device selection:
 * [WakeWordInputDevices.dedupeAndPrefer]. Native enumeration (`list` /
 * `defaultDevice`) needs a sound card and is exercised in production only.
 */
class WakeWordInputDevicesTest {

    private fun device(name: String, index: Int = 0) = WakeWordInputDevice(name, index)

    @Test
    fun `deduplicates identical trimmed names keeping first occurrence`() {
        val all = listOf(device("Mic A", 0), device("Mic A", 3), device("Mic B", 1))

        val result = WakeWordInputDevices.dedupeAndPrefer(all, default = null)

        assertEquals(listOf(device("Mic A", 0), device("Mic B", 1)), result)
    }

    @Test
    fun `moves the default device to the front`() {
        val all = listOf(device("Mic A", 0), device("Mic B", 1), device("Mic C", 2))

        val result = WakeWordInputDevices.dedupeAndPrefer(all, default = "Mic B")

        assertEquals(listOf("Mic B", "Mic A", "Mic C"), result.map { it.name })
    }

    @Test
    fun `keeps every input-filtered device (rejects nothing)`() {
        val all = listOf(device("Mic A", 0), device("Mic B", 1), device("Mic C", 2))

        val result = WakeWordInputDevices.dedupeAndPrefer(all, default = "Mic A")

        assertEquals(3, result.size)
        assertEquals(setOf("Mic A", "Mic B", "Mic C"), result.map { it.name }.toSet())
    }

    @Test
    fun `skips blank names and trims for dedupe`() {
        val all = listOf(device("  ", 0), device("Mic A ", 1), device(" Mic A", 2))

        val result = WakeWordInputDevices.dedupeAndPrefer(all, default = null)

        assertEquals(listOf(device("Mic A", 1)), result)
    }

    @Test
    fun `preserves order when default is null or absent`() {
        val all = listOf(device("Mic A", 0), device("Mic B", 1))

        assertEquals(listOf("Mic A", "Mic B"), WakeWordInputDevices.dedupeAndPrefer(all, null).map { it.name })
        assertEquals(
            listOf("Mic A", "Mic B"),
            WakeWordInputDevices.dedupeAndPrefer(all, "Nonexistent").map { it.name }
        )
    }
}
