package org.jarvis.desktop.palette

/**
 * A single searchable entry in the command palette: either a navigation
 * target or a key action. Deliberately free of any UI/action wiring so the
 * filtering logic below stays pure and unit-testable.
 */
data class PaletteEntry(
    val id: String,
    val label: String,
    val category: String,
    val keywords: List<String> = emptyList()
)

/**
 * Ranks and filters palette entries by a free-text query. Pure function —
 * no JavaFX dependency — so it can be exercised directly in unit tests.
 */
object CommandPaletteFilter {

    fun filter(entries: List<PaletteEntry>, query: String): List<PaletteEntry> =
        filter(entries, query) { it }

    /**
     * Generic overload so callers can filter a list of richer items (e.g. an
     * entry bundled with its action) without duplicating the ranking logic.
     */
    fun <T> filter(items: List<T>, query: String, entryOf: (T) -> PaletteEntry): List<T> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return items

        val needle = trimmed.lowercase()
        return items
            .mapNotNull { item -> rank(entryOf(item), needle)?.let { rank -> rank to item } }
            .sortedBy { it.first }
            .map { it.second }
    }

    /** Lower is better. Null means "no match". */
    private fun rank(entry: PaletteEntry, needle: String): Int? {
        val haystacks = (listOf(entry.label, entry.category) + entry.keywords).map { it.lowercase() }
        return when {
            haystacks.any { it == needle } -> 0
            haystacks.any { it.startsWith(needle) } -> 1
            haystacks.any { it.contains(needle) } -> 2
            else -> null
        }
    }
}
