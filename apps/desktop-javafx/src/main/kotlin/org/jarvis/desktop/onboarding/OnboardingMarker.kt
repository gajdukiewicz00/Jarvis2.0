package org.jarvis.desktop.onboarding

import org.jarvis.launcher.JarvisPaths
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * First-run marker for the unified desktop shell.
 *
 * The onboarding wizard is shown once, the first time the shell starts with
 * no marker file present. Completing (or skipping) the wizard writes the
 * marker so subsequent launches go straight to the shell.
 */
class OnboardingMarker(
    private val markerPath: Path = defaultMarkerPath()
) {
    fun isComplete(): Boolean = Files.exists(markerPath)

    fun markComplete() {
        val parent = markerPath.parent
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent)
        }
        if (!Files.exists(markerPath)) {
            Files.writeString(markerPath, Instant.now().toString())
        }
    }

    /** Test/support hook to force the wizard to show again. */
    fun reset() {
        Files.deleteIfExists(markerPath)
    }

    companion object {
        fun defaultMarkerPath(): Path =
            JarvisPaths.root.resolve("desktop").resolve("onboarding-complete")
    }
}
