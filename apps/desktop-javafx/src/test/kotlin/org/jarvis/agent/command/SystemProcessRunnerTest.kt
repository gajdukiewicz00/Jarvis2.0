package org.jarvis.agent.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Exercises the production [DefaultDesktopActions.SystemProcessRunner] seam.
 *
 * <p>The benign happy-path assertions use POSIX coreutils ({@code true},
 * {@code echo}) and only run on non-Windows hosts; the failure-path
 * assertions use a guaranteed-missing binary, so {@code ProcessBuilder.start}
 * throws immediately and no process is ever spawned. No hardware or
 * side-effecting command is touched.</p>
 */
class SystemProcessRunnerTest {

    private val runner = DefaultDesktopActions.SystemProcessRunner()
    private val isPosix = !System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
    private val missingBinary = "jarvis-nonexistent-binary-${System.nanoTime()}"

    @BeforeEach
    fun requirePosix() {
        assumeTrue(isPosix, "POSIX coreutils required for the happy-path assertions")
    }

    @Test
    fun `run captures a zero exit code from true`() {
        val result = runner.run(listOf("true"), timeoutSec = 5)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `run captures stdout from echo`() {
        val result = runner.run(listOf("echo", "jarvis-hello"), timeoutSec = 5)
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("jarvis-hello"))
    }

    @Test
    fun `run returns minus one and no output when the binary is missing`() {
        val result = runner.run(listOf(missingBinary), timeoutSec = 5)
        assertEquals(-1, result.exitCode)
        assertEquals("", result.stdout)
        assertNotNull(result.stderr)
    }

    @Test
    fun `spawn reports a pid for a launchable process`() {
        val outcome = runner.spawn(listOf("true"))
        assertTrue(outcome.spawned)
        assertNotNull(outcome.pid)
    }

    @Test
    fun `spawn reports failure when the binary is missing`() {
        val outcome = runner.spawn(listOf(missingBinary))
        assertFalse(outcome.spawned)
        assertNull(outcome.pid)
        assertNotNull(outcome.error)
    }
}
