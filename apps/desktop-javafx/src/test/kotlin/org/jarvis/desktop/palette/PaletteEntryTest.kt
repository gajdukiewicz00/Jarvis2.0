package org.jarvis.desktop.palette

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaletteEntryTest {

    private val entries = listOf(
        PaletteEntry("brain", "Brain / AI Chat", "Navigate", listOf("Brain / AI Chat")),
        PaletteEntry("memory", "Memory", "Navigate", listOf("Memory")),
        PaletteEntry("finance", "Finance", "Navigate", listOf("Finance", "Bank parser")),
        PaletteEntry("panic-engage", "Engage panic kill-switch", "Safety")
    )

    @Test
    fun `blank query returns every entry unchanged`() {
        assertEquals(entries, CommandPaletteFilter.filter(entries, ""))
        assertEquals(entries, CommandPaletteFilter.filter(entries, "   "))
    }

    @Test
    fun `matching is case-insensitive`() {
        val result = CommandPaletteFilter.filter(entries, "MEMORY")

        assertEquals(listOf("Memory"), result.map { it.label })
    }

    @Test
    fun `matches against keywords in addition to the label`() {
        val result = CommandPaletteFilter.filter(entries, "bank")

        assertEquals(listOf("Finance"), result.map { it.label })
    }

    @Test
    fun `matches against category`() {
        val result = CommandPaletteFilter.filter(entries, "safety")

        assertEquals(listOf("Engage panic kill-switch"), result.map { it.label })
    }

    @Test
    fun `ranks exact and prefix matches ahead of plain substring matches`() {
        val ranked = listOf(
            PaletteEntry("a", "Memory", "Navigate"),
            PaletteEntry("b", "Semantic Memory Search", "Navigate"),
            PaletteEntry("c", "Memory Tools", "Navigate")
        )

        val result = CommandPaletteFilter.filter(ranked, "memory")

        assertEquals(listOf("Memory", "Memory Tools", "Semantic Memory Search"), result.map { it.label })
    }

    @Test
    fun `entries with no match are excluded`() {
        val result = CommandPaletteFilter.filter(entries, "zzz-does-not-exist")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `generic overload filters wrapped items via the supplied entry selector`() {
        data class Wrapped(val entry: PaletteEntry, val payload: Int)
        val wrapped = entries.mapIndexed { index, entry -> Wrapped(entry, index) }

        val result = CommandPaletteFilter.filter(wrapped, "finance") { it.entry }

        assertEquals(listOf("Finance"), result.map { it.entry.label })
    }
}
