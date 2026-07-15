package org.jarvis.launcher

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Exercises [JarvisPaths.getProjectRoot] filesystem-driven branches by steering
 * `user.dir` (a system property, safe to mutate) at a temp directory. No env
 * vars are set and no real `~/.jarvis` files are written; the process working
 * directory is restored after each case.
 *
 * These cases are skipped when the ambient environment would short-circuit the
 * resolver before it reaches the `user.dir` branch (JARVIS_PROJECT_ROOT set, or
 * a RELEASE_SOURCE file present), keeping the assertions deterministic.
 */
class JarvisPathsProjectRootTest {

    private fun envWouldShortCircuit(): Boolean {
        return System.getenv("JARVIS_PROJECT_ROOT") != null ||
            Files.isRegularFile(JarvisPaths.releaseSourceFile)
    }

    private fun makeValidProjectRoot(dir: Path) {
        Files.createFile(dir.resolve("jarvis-launch.sh"))
        Files.createFile(dir.resolve("pom.xml"))
        Files.createDirectories(dir.resolve("apps"))
    }

    private inline fun <T> withUserDir(dir: String, block: () -> T): T {
        val previous = System.getProperty("user.dir")
        System.setProperty("user.dir", dir)
        return try {
            block()
        } finally {
            if (previous != null) System.setProperty("user.dir", previous)
        }
    }

    @Test
    fun `resolves current working directory when it is a valid project root`(@TempDir tempDir: Path) {
        assumeTrue(!envWouldShortCircuit(), "ambient env resolves project root before user.dir")
        makeValidProjectRoot(tempDir)

        val resolved = withUserDir(tempDir.toString()) { JarvisPaths.getProjectRoot() }

        assertEquals(Paths.get(tempDir.toString()), resolved)
    }

    @Test
    fun `returns a non-null path when working directory is not a project root`(@TempDir tempDir: Path) {
        assumeTrue(!envWouldShortCircuit(), "ambient env resolves project root before user.dir")
        // Deliberately no marker files: forces the resolver past the current-dir
        // and parent-of-parent checks into the common-locations / last-resort tail.
        val resolved = withUserDir(tempDir.toString()) { JarvisPaths.getProjectRoot() }

        assertNotNull(resolved)
    }

    @Test
    fun `directory missing apps folder is not accepted as project root`(@TempDir tempDir: Path) {
        assumeTrue(!envWouldShortCircuit(), "ambient env resolves project root before user.dir")
        // Only two of the three markers exist: isValidProjectRoot must reject it,
        // so getProjectRoot cannot return this directory.
        Files.createFile(tempDir.resolve("jarvis-launch.sh"))
        Files.createFile(tempDir.resolve("pom.xml"))

        val resolved = withUserDir(tempDir.toString()) { JarvisPaths.getProjectRoot() }

        assertNotNull(resolved)
        // The incomplete directory is never the answer.
        org.junit.jupiter.api.Assertions.assertNotEquals(Paths.get(tempDir.toString()), resolved)
    }
}
