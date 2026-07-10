package org.jarvis.desktop.service

/**
 * Where an [AppEntry] was discovered. Drives launch strategy and trust level:
 * DESKTOP/FLATPAK/SNAP entries come from curated `.desktop` files and are considered
 * safe to launch directly; PATH entries are a low-confidence fallback and are subject
 * to the dangerous-binary blocklist.
 */
enum class AppSource { DESKTOP, FLATPAK, SNAP, PATH }

/**
 * How an [AppEntry] should be started.
 * - GTK_LAUNCH  -> `gtk-launch <desktop-id>` (preferred for `.desktop` apps)
 * - GIO_LAUNCH  -> `gio launch <desktop-file-path>` (documented fallback for GTK_LAUNCH)
 * - FLATPAK_RUN -> `flatpak run <app-id>`
 * - EXEC        -> run the parsed Exec argv directly (PATH fallback)
 */
enum class LaunchMethod { GTK_LAUNCH, GIO_LAUNCH, FLATPAK_RUN, EXEC }

/**
 * Immutable description of a single launchable application.
 *
 * @param id desktop id (filename without `.desktop`) or, for PATH entries, the exec basename.
 * @param exec cleaned Exec command (field codes such as %U/%F stripped); may be empty.
 */
data class AppEntry(
    val id: String,
    val name: String,
    val genericName: String? = null,
    val comment: String? = null,
    val exec: String,
    val keywords: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val wmClass: String? = null,
    val desktopFilePath: String? = null,
    val source: AppSource,
    val launchMethod: LaunchMethod,
)

/**
 * Outcome of resolving a fuzzy spoken name to an app.
 *
 * - [Launch]   best confidence >= [SystemAppIndex.CONFIDENCE_LAUNCH]; act immediately.
 * - [Clarify]  best confidence in [CONFIDENCE_CLARIFY, CONFIDENCE_LAUNCH); ask the user.
 * - [NotFound] best confidence below [SystemAppIndex.CONFIDENCE_CLARIFY]; nothing to launch.
 */
sealed interface AppResolution {
    data class Launch(val entry: AppEntry, val confidence: Double) : AppResolution

    data class Clarify(
        val candidates: List<AppEntry>,
        val confidences: List<Double>,
    ) : AppResolution

    data class NotFound(
        val query: String,
        val suggestions: List<AppEntry>,
    ) : AppResolution
}
