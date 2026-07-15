package org.jarvis.launcher

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Modifier
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Covers [ProcessRunner]'s spawn-free decision logic and guard branches.
 *
 * The happy path spawns a real OS process (via a `flock` wrapper) and is
 * deliberately NOT exercised here — per the harness rules we never launch
 * real processes. Instead we drive the early-return guard branches directly
 * by toggling the run/start flags through reflection, and verify the
 * idle-state accessors (`isRunning`, `getPid`, `stop`).
 */
class ProcessRunnerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun newRunner(): ProcessRunner =
        ProcessRunner(logFile = tempDir.resolve("proc.log")) { /* ignore output */ }

    private fun instanceFlag(runner: ProcessRunner, name: String): AtomicBoolean {
        val field = ProcessRunner::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(runner) as AtomicBoolean
    }

    private fun startGuard(): AtomicBoolean {
        // The companion's `startGuard` is compiled to a private static
        // AtomicBoolean on the outer class; locate it by type so the test is
        // resilient to the exact generated field name.
        val field = ProcessRunner::class.java.declaredFields.single {
            Modifier.isStatic(it.modifiers) && it.type == AtomicBoolean::class.java
        }
        field.isAccessible = true
        return field.get(null) as AtomicBoolean
    }

    @Test
    fun `idle runner reports not running with no pid`() {
        val runner = newRunner()
        assertFalse(runner.isRunning())
        assertNull(runner.getPid())
    }

    @Test
    fun `stopping an idle runner returns false`() {
        val runner = newRunner()
        assertFalse(runner.stop())
    }

    @Test
    fun `start is ignored when a process is already marked running`() {
        val runner = newRunner()
        val running = instanceFlag(runner, "isRunning")
        running.set(true)
        try {
            val future = runner.start(Paths.get("/does/not/exist.sh"))
            assertEquals(0, future.get())
        } finally {
            running.set(false)
        }
    }

    @Test
    fun `start is skipped when a global start is already in progress`() {
        val runner = newRunner()
        val guard = startGuard()
        // Precondition: instance flag idle, global start guard held elsewhere.
        instanceFlag(runner, "isRunning").set(false)
        guard.set(true)
        try {
            val future = runner.start(Paths.get("/does/not/exist.sh"))
            assertEquals(1, future.get())
            // Guard branch must reset the instance running flag it just set.
            assertFalse(instanceFlag(runner, "isRunning").get())
        } finally {
            guard.set(false)
        }
    }
}
