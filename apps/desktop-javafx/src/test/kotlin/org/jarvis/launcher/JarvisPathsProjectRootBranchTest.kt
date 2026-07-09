package org.jarvis.launcher

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Complements [JarvisPathsProjectRootTest] with the [JarvisPaths.getProjectRoot]
 * branches it leaves uncovered:
 *
 *  - the "assumed root" fallback that walks two levels up from `user.dir`
 *    (the `apps/desktop-javafx` layout) when the working dir itself is not a root;
 *  - the remaining `isValidProjectRoot` short-circuit operands: a directory that
 *    is missing `jarvis-launch.sh`, and one missing `pom.xml`. The sibling test
 *    already covers the missing-`apps` operand.
 *
 * Only `user.dir` (a mutable system property) is steered and only temp
 * directories are touched — no env vars are set and no real `~/.jarvis` files are
 * written. Cases are skipped when the ambient environment would resolve the root
 * before the `user.dir` logic is reached.
 */
class JarvisPathsProjectRootBranchTest {

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
    fun `resolves grandparent when working dir is two levels below a valid root`(@TempDir tempDir: Path) {
        assumeTrue(!envWouldShortCircuit(), "ambient env resolves project root before user.dir")
        makeValidProjectRoot(tempDir)
        // user.dir mimics apps/desktop-javafx: not a root itself, but grandparent is.
        val nested = tempDir.resolve("apps").resolve("desktop-javafx")
        Files.createDirectories(nested)

        val resolved = withUserDir(nested.toString()) { JarvisPaths.getProjectRoot() }

        assertEquals(Paths.get(tempDir.toString()), resolved)
    }

    @Test
    fun `directory missing launch script is not accepted as project root`(@TempDir tempDir: Path) {
        assumeTrue(!envWouldShortCircuit(), "ambient env resolves project root before user.dir")
        // pom.xml + apps present, but no jarvis-launch.sh -> first operand fails.
        Files.createFile(tempDir.resolve("pom.xml"))
        Files.createDirectories(tempDir.resolve("apps"))

        val resolved = withUserDir(tempDir.toString()) { JarvisPaths.getProjectRoot() }

        assertNotNull(resolved)
        assertNotEquals(Paths.get(tempDir.toString()), resolved)
    }

    @Test
    fun `directory missing pom is not accepted as project root`(@TempDir tempDir: Path) {
        assumeTrue(!envWouldShortCircuit(), "ambient env resolves project root before user.dir")
        // jarvis-launch.sh + apps present, but no pom.xml -> second operand fails.
        Files.createFile(tempDir.resolve("jarvis-launch.sh"))
        Files.createDirectories(tempDir.resolve("apps"))

        val resolved = withUserDir(tempDir.toString()) { JarvisPaths.getProjectRoot() }

        assertNotNull(resolved)
        assertNotEquals(Paths.get(tempDir.toString()), resolved)
    }
}
