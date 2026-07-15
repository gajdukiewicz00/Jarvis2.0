package org.jarvis.desktop.service

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

/**
 * Indexes installed Linux applications (`.desktop` files + PATH executables) and resolves a
 * fuzzy spoken name to a launchable app.
 *
 * The scan directories and PATH scanning are constructor-injectable so tests can point at a
 * temp fixture directory and run fully offline/deterministically. No time or randomness is used
 * in matching, so [resolve] is reproducible for a given index.
 *
 * @param scanDirs `.desktop` directories to parse (default = the real system + user dirs).
 * @param pathDirs `$PATH` directories to scan for executables (default = the process `$PATH`).
 * @param indexPathExecutables when false, PATH scanning is skipped entirely.
 */
class SystemAppIndex(
    scanDirs: List<Path> = defaultDesktopDirs(),
    pathDirs: List<Path> = defaultPathDirs(),
    indexPathExecutables: Boolean = true,
) {
    private val logger = LoggerFactory.getLogger(SystemAppIndex::class.java)

    private val entries: List<AppEntry> = buildIndex(scanDirs, pathDirs, indexPathExecutables)

    /** PATH/EXEC entries whose binary is blocklisted are excluded from anything launchable. */
    private val searchable: List<AppEntry> =
        entries.filterNot { it.source == AppSource.PATH && isDangerous(it.exec) }

    init {
        logger.info(
            "📇 Indexed {} apps ({} searchable) from {} desktop dir(s)",
            entries.size, searchable.size, scanDirs.size,
        )
    }

    /** All parsed entries (NoDisplay/Hidden/non-Application already skipped). */
    fun allEntries(): List<AppEntry> = entries

    // ==================== Resolution ====================

    /**
     * Resolve a fuzzy, possibly Russian, spoken name to an [AppResolution].
     * Exact name matches score 1.0 and always win.
     */
    fun resolve(query: String): AppResolution {
        val normalized = AppTextMatch.normalize(query)
        if (normalized.isBlank()) return AppResolution.NotFound(query, emptyList())

        val forms = AppTextMatch.queryForms(normalized)
        val ranked = searchable
            .map { entry -> entry to scoreEntry(entry, forms) }
            .sortedWith(
                compareByDescending<Pair<AppEntry, Double>> { it.second }
                    .thenBy { sourcePriority(it.first.source) }
                    .thenBy { it.first.id },
            )

        val best = ranked.firstOrNull() ?: return AppResolution.NotFound(query, emptyList())
        val bestScore = best.second

        return when {
            bestScore >= CONFIDENCE_LAUNCH -> AppResolution.Launch(best.first, bestScore)
            bestScore >= CONFIDENCE_CLARIFY -> {
                val candidates = ranked.filter { it.second >= CONFIDENCE_CLARIFY }.take(MAX_SUGGESTIONS)
                AppResolution.Clarify(candidates.map { it.first }, candidates.map { it.second })
            }
            else -> AppResolution.NotFound(query, ranked.take(MAX_SUGGESTIONS).map { it.first })
        }
    }

    private fun scoreEntry(entry: AppEntry, forms: List<String>): Double {
        var best = 0.0
        for (form in forms) {
            for (field in matchFields(entry)) {
                val score = AppTextMatch.similarity(form, field)
                if (score > best) best = score
                if (best >= 1.0) return 1.0
            }
        }
        return best
    }

    private fun matchFields(entry: AppEntry): List<String> = buildList {
        add(AppTextMatch.normalize(entry.name))
        entry.genericName?.let { add(AppTextMatch.normalize(it)) }
        add(AppTextMatch.normalize(entry.id))
        entry.keywords.forEach { add(AppTextMatch.normalize(it)) }
    }

    private fun sourcePriority(source: AppSource): Int = when (source) {
        AppSource.DESKTOP -> 0
        AppSource.FLATPAK -> 1
        AppSource.SNAP -> 2
        AppSource.PATH -> 3
    }

    // ==================== Launch ====================

    /** Build the argv to launch [entry]. Does NOT start a process — the caller runs it. */
    fun launchCommand(entry: AppEntry): List<String> = when (entry.launchMethod) {
        LaunchMethod.GTK_LAUNCH -> listOf("gtk-launch", entry.id)
        LaunchMethod.GIO_LAUNCH -> listOf("gio", "launch", entry.desktopFilePath ?: entry.id)
        LaunchMethod.FLATPAK_RUN -> listOf("flatpak", "run", entry.id)
        LaunchMethod.EXEC -> splitExec(entry.exec).ifEmpty { listOf(entry.id) }
    }

    /** True when the first token of [exec] is a blocklisted binary that must not be launched directly. */
    fun isDangerous(exec: String): Boolean {
        val binary = splitExec(exec).firstOrNull()?.substringAfterLast('/') ?: return false
        if (binary.isEmpty()) return false
        return binary in BLOCKED_BINARIES || binary.startsWith("mkfs.")
    }

    // ==================== Indexing ====================

    private fun buildIndex(
        scanDirs: List<Path>,
        pathDirs: List<Path>,
        indexPathExecutables: Boolean,
    ): List<AppEntry> {
        val desktop = scanDirs.flatMap { readDesktopEntries(it) }
        val path = if (indexPathExecutables) scanPathExecutables(pathDirs) else emptyList()
        return dedupeById(desktop + path)
    }

    /** Keep the first entry seen per id, so curated desktop entries win over PATH fallbacks. */
    private fun dedupeById(all: List<AppEntry>): List<AppEntry> {
        val byId = LinkedHashMap<String, AppEntry>()
        for (entry in all) byId.putIfAbsent(entry.id, entry)
        return byId.values.toList()
    }

    private fun readDesktopEntries(dir: Path): List<AppEntry> {
        val directory = dir.toFile()
        if (!directory.isDirectory) return emptyList()
        val files = (directory.listFiles { f -> f.isFile && f.name.endsWith(DESKTOP_SUFFIX) } ?: emptyArray())
            .sortedBy { it.name }
        return files.mapNotNull { parseDesktopFile(it, dir) }
    }

    private fun parseDesktopFile(file: File, dir: Path): AppEntry? {
        val fields = LinkedHashMap<String, String>()
        var inDesktopEntry = false
        for (raw in runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                inDesktopEntry = line == "[Desktop Entry]"
                continue
            }
            if (!inDesktopEntry) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            if (key.contains('[')) continue // skip localized keys (Name[ru]=...)
            fields.putIfAbsent(key, line.substring(eq + 1).trim())
        }

        if (fields["Type"] != "Application") return null
        if (fields["NoDisplay"].toBoolean() || fields["Hidden"].toBoolean()) return null
        val name = fields["Name"] ?: return null

        val dirPath = dir.toString()
        val source = when {
            dirPath.contains("flatpak") -> AppSource.FLATPAK
            dirPath.contains("snap") -> AppSource.SNAP
            else -> AppSource.DESKTOP
        }
        val launchMethod = if (source == AppSource.FLATPAK) LaunchMethod.FLATPAK_RUN else LaunchMethod.GTK_LAUNCH

        return AppEntry(
            id = file.name.removeSuffix(DESKTOP_SUFFIX),
            name = name,
            genericName = fields["GenericName"],
            comment = fields["Comment"],
            exec = fields["Exec"]?.let { cleanExec(it) } ?: "",
            keywords = splitList(fields["Keywords"]),
            categories = splitList(fields["Categories"]),
            wmClass = fields["StartupWMClass"],
            desktopFilePath = file.absolutePath,
            source = source,
            launchMethod = launchMethod,
        )
    }

    private fun scanPathExecutables(pathDirs: List<Path>): List<AppEntry> {
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<AppEntry>()
        for (dir in pathDirs) {
            val directory = dir.toFile()
            if (!directory.isDirectory) continue
            val files = (directory.listFiles { f -> f.isFile && f.canExecute() } ?: emptyArray())
                .sortedBy { it.name }
            for (file in files) {
                if (!seen.add(file.name)) continue
                result.add(
                    AppEntry(
                        id = file.name,
                        name = file.name,
                        exec = file.name,
                        source = AppSource.PATH,
                        launchMethod = LaunchMethod.EXEC,
                    ),
                )
            }
        }
        return result
    }

    private fun splitList(raw: String?): List<String> =
        raw?.split(';')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

    /** Strip Exec field codes (%U %F %u %f %i %c %k), restore %%, and collapse whitespace. */
    private fun cleanExec(raw: String): String {
        val restored = raw.replace("%%", PERCENT_PLACEHOLDER)
        val stripped = restored.replace(EXEC_FIELD_CODES, "")
        return stripped.replace(PERCENT_PLACEHOLDER, "%").replace(WHITESPACE, " ").trim()
    }

    /** Minimal argv splitter honoring double quotes; adequate for `.desktop` Exec lines. */
    private fun splitExec(exec: String): List<String> {
        val argv = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote = false
        for (ch in exec) {
            when {
                ch == '"' -> inQuote = !inQuote
                ch.isWhitespace() && !inQuote -> {
                    if (current.isNotEmpty()) {
                        argv.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) argv.add(current.toString())
        return argv
    }

    companion object {
        /** Best confidence at/above which [resolve] returns [AppResolution.Launch]. */
        const val CONFIDENCE_LAUNCH: Double = 0.85

        /** Best confidence at/above which [resolve] returns [AppResolution.Clarify]. */
        const val CONFIDENCE_CLARIFY: Double = 0.60

        private const val MAX_SUGGESTIONS = 3
        private const val DESKTOP_SUFFIX = ".desktop"
        private const val PERCENT_PLACEHOLDER = "\u0000"

        private val WHITESPACE = Regex("\\s+")
        private val EXEC_FIELD_CODES = Regex("%[UFufick]")

        /** Binaries that must never be launched directly from a PATH/EXEC entry. */
        private val BLOCKED_BINARIES: Set<String> = setOf(
            "rm", "shutdown", "reboot", "poweroff", "halt", "mkfs", "dd",
            "sudo", "su", "bash", "sh", "zsh", "fish", "dash",
            "chmod", "chown", "kill", "killall",
        )

        private fun defaultDesktopDirs(): List<Path> {
            val home = System.getProperty("user.home") ?: "."
            return listOf(
                "/usr/share/applications",
                "$home/.local/share/applications",
                "/var/lib/flatpak/exports/share/applications",
                "$home/.local/share/flatpak/exports/share/applications",
                "/var/lib/snapd/desktop/applications",
            ).map { Path.of(it) }
        }

        private fun defaultPathDirs(): List<Path> =
            (System.getenv("PATH") ?: "")
                .split(File.pathSeparatorChar)
                .filter { it.isNotBlank() }
                .map { Path.of(it) }
    }
}
