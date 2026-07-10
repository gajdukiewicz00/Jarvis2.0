package org.jarvis.desktop.service

/**
 * Deterministic text-matching helpers for [SystemAppIndex]: query normalization,
 * a RU->app alias/transliteration dictionary, token splitting, and a Levenshtein-ratio
 * similarity. Nothing here uses time or randomness, so scoring is fully reproducible.
 */
internal object AppTextMatch {

    private val WHITESPACE = Regex("\\s+")
    private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /**
     * Common Russian spoken tokens/phrases -> latin app tokens. Keys are already normalized
     * (lowercase, e-with-diaeresis folded). Targets are matched against app name/id/keywords.
     */
    private val ALIASES: Map<String, List<String>> = mapOf(
        "телеграм" to listOf("telegram"),
        "телеграмм" to listOf("telegram"),
        "вес скотт" to listOf("vs code", "visual studio code", "code"),
        "вс код" to listOf("vs code", "visual studio code", "code"),
        "визуал студио код" to listOf("vs code", "visual studio code", "code"),
        "скотт" to listOf("code", "vs code"),
        "калькулятор" to listOf("calculator"),
        "настройки" to listOf("settings"),
        "браузер" to listOf("browser", "firefox"),
        "файлы" to listOf("files", "nautilus"),
        "проводник" to listOf("files", "nautilus"),
        "терминал" to listOf("terminal"),
    )

    /** Lowercase, trim, fold e-with-diaeresis to e, and collapse internal whitespace. */
    fun normalize(raw: String): String =
        raw.trim().lowercase().replace('ё', 'е').replace(WHITESPACE, " ").trim()

    /** Split into non-empty alphanumeric tokens (keeps Cyrillic; drops punctuation/dots). */
    fun tokens(value: String): List<String> =
        value.split(TOKEN_SPLIT).filter { it.isNotBlank() }

    /**
     * Expand a normalized query into candidate match strings via the alias dictionary.
     * Always includes the original query so exact/latin matches still work.
     */
    fun queryForms(normalizedQuery: String): List<String> {
        val forms = LinkedHashSet<String>()
        forms.add(normalizedQuery)
        ALIASES[normalizedQuery]?.let { forms.addAll(it) }
        tokens(normalizedQuery).forEach { token -> ALIASES[token]?.let { forms.addAll(it) } }
        ALIASES.forEach { (key, targets) ->
            if (key.contains(' ') && normalizedQuery.contains(key)) forms.addAll(targets)
        }
        return forms.toList()
    }

    /**
     * Similarity in 0..1 between two normalized strings. Combines a whole-string Levenshtein
     * ratio with a per-query-token best-match average, so a single-token query (e.g. "telegram")
     * scores 1.0 against a multi-word name that contains it ("telegram desktop").
     */
    fun similarity(query: String, field: String): Double {
        if (query.isBlank() || field.isBlank()) return 0.0
        if (query == field) return 1.0
        val whole = levenshteinRatio(query, field)
        val queryTokens = tokens(query)
        val fieldTokens = tokens(field)
        if (queryTokens.isEmpty() || fieldTokens.isEmpty()) return whole
        val perToken = queryTokens.map { qt -> fieldTokens.maxOf { ft -> tokenSimilarity(qt, ft) } }
        return maxOf(whole, perToken.average())
    }

    private fun tokenSimilarity(a: String, b: String): Double =
        if (a == b) 1.0 else levenshteinRatio(a, b)

    /** 1.0 - normalized edit distance, in 0..1. */
    fun levenshteinRatio(a: String, b: String): Double {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val curr = IntArray(b.length + 1)
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = curr
        }
        return prev[b.length]
    }
}
