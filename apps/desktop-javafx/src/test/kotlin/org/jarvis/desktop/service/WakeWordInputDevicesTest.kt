package org.jarvis.desktop.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the only non-hardware part of input-device selection:
 * [WakeWordInputDevices.classify]. Native enumeration (`list` /
 * `listWithClassification`) needs a sound card and is exercised in production only.
 */
class WakeWordInputDevicesTest {

    private fun device(name: String, index: Int = 0) = WakeWordInputDevice(name, index)

    @Test
    fun `rejects the alsa_playback default device`() {
        val all = listOf(device("alsa_playback.java [default]", 0), device("C4K Microphone", 1))

        val result = WakeWordInputDevices.classify(all, default = "alsa_playback.java [default]")

        assertFalse(result.accepted.any { it.name == "alsa_playback.java [default]" })
        val rejected = result.rejected.single()
        assertEquals("alsa_playback.java [default]", rejected.name)
        assertEquals("playback/output/monitor device", rejected.reason)
        // A rejected default must never be selected — the real mic is chosen instead.
        assertEquals("C4K Microphone", result.accepted.first().name)
    }

    @Test
    fun `rejects output monitor sink and speaker devices`() {
        val all = listOf(
            device("HDMI Output", 0),
            device("Monitor of Built-in Audio", 1),
            device("Null Sink", 2),
            device("Desktop Speaker", 3),
            device("USB Microphone", 4)
        )

        val result = WakeWordInputDevices.classify(all, default = null)

        assertEquals(listOf("USB Microphone"), result.accepted.map { it.name })
        assertEquals(
            setOf("HDMI Output", "Monitor of Built-in Audio", "Null Sink", "Desktop Speaker"),
            result.rejected.map { it.name }.toSet()
        )
    }

    @Test
    fun `accepts and fronts keyword mics before a non-keyword device`() {
        val all = listOf(
            device("Some Generic Line", 0),
            device("C4K Camera Mic", 1),
            device("T1 Headset", 2),
            device("plughw CARD Device", 3),
            device("USB Audio", 4)
        )

        val result = WakeWordInputDevices.classify(all, default = null)

        val names = result.accepted.map { it.name }
        // All five are accepted (none look like playback).
        assertEquals(5, names.size)
        // Every keyword device is ordered before the non-keyword "Some Generic Line".
        val genericIndex = names.indexOf("Some Generic Line")
        listOf("C4K Camera Mic", "T1 Headset", "plughw CARD Device", "USB Audio").forEach { mic ->
            assertTrue(names.indexOf(mic) < genericIndex, "$mic should precede the non-keyword device")
        }
    }

    @Test
    fun `deduplicates identical trimmed names keeping first occurrence`() {
        val all = listOf(device("USB Mic", 0), device("USB Mic", 3), device("Other Mic", 1))

        val result = WakeWordInputDevices.classify(all, default = null)

        assertEquals(listOf(device("USB Mic", 0), device("Other Mic", 1)), result.accepted)
    }

    @Test
    fun `skips blank names and trims for dedupe`() {
        val all = listOf(device("  ", 0), device("USB Mic ", 1), device(" USB Mic", 2))

        val result = WakeWordInputDevices.classify(all, default = null)

        assertEquals(listOf(device("USB Mic", 1)), result.accepted)
    }

    @Test
    fun `fronts an accepted preferred default among the preferred mics`() {
        val all = listOf(device("USB Mic A", 0), device("C4K Mic B", 1))

        val result = WakeWordInputDevices.classify(all, default = "C4K Mic B")

        // Both are preferred; the default is moved to the very front.
        assertEquals(listOf("C4K Mic B", "USB Mic A"), result.accepted.map { it.name })
    }

    @Test
    fun `never fronts a plain default ahead of a keyword mic`() {
        val all = listOf(device("C4K Mic", 0), device("Generic Line", 1))

        val result = WakeWordInputDevices.classify(all, default = "Generic Line")

        // "Generic Line" is not a preferred device, so C4K stays first.
        assertEquals(listOf("C4K Mic", "Generic Line"), result.accepted.map { it.name })
    }

    @Test
    fun `looksLikePlayback matches reject substrings case-insensitively`() {
        assertTrue(WakeWordInputDevices.looksLikePlayback("alsa_PLAYBACK.java [default]"))
        assertTrue(WakeWordInputDevices.looksLikePlayback("Monitor of Sink"))
        assertFalse(WakeWordInputDevices.looksLikePlayback("C4K Microphone"))
    }
}
