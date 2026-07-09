package org.jarvis.desktop.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Covers the pure DECISION / argv-selection branches of [SystemControlService]
 * that resolve to a result WITHOUT ever launching a side-effecting external
 * process (no volume change, keystroke injection, screen lock, notification, or
 * media control ever runs).
 *
 * Only three kinds of paths are exercised here:
 *  1. Guarded early-returns that fail before any ProcessBuilder is touched
 *     ([windowAction] / [executeScenario] unknown-action branches).
 *  2. The "unknown app" launch branch, which only ever attempts to exec a
 *     guaranteed-absent binary — start() fails fast (IOException, no process is
 *     created) and the method reports [Result.failure].
 *  3. [SystemControlService.resumeMediaStatic] no-op guard, which returns before
 *     spawning playerctl when nothing was paused by us.
 *
 * The process helpers ([executeProcess] / [executeCheckedCommand]) are covered
 * by the sibling SystemControlServiceTest and are intentionally not repeated.
 */
class SystemControlServiceDecisionTest {

    private val service = SystemControlService()

    // ---- windowAction: unknown action is rejected before any xdotool call ----

    @Test
    fun `windowAction returns failure for an unknown action without executing`() {
        val result = service.windowAction("NOT_A_REAL_ACTION")

        assertTrue(result.isFailure, "unknown window action must fail")
        val error = requireNotNull(result.exceptionOrNull())
        assertInstanceOf(IllegalArgumentException::class.java, error)
        assertTrue(
            error.message.orEmpty().contains("NOT_A_REAL_ACTION"),
            "error should name the offending action, was: ${error.message}"
        )
    }

    @Test
    fun `windowAction unknown action is rejected regardless of target`() {
        val result = service.windowAction("bogus", target = "some-window")

        assertTrue(result.isFailure)
        assertInstanceOf(IllegalArgumentException::class.java, result.exceptionOrNull())
    }

    // ---- executeScenario: unknown scenario is rejected before any process ----

    @Test
    fun `executeScenario returns failure for an unknown scenario without executing`() {
        val result = service.executeScenario("no-such-scenario")

        assertTrue(result.isFailure, "unknown scenario must fail")
        val error = requireNotNull(result.exceptionOrNull())
        assertInstanceOf(IllegalArgumentException::class.java, error)
        assertTrue(
            error.message.orEmpty().contains("no-such-scenario"),
            "error should name the offending scenario, was: ${error.message}"
        )
    }

    // ---- openApp: unknown app resolves to the fallback argv and fails safely ----

    @Test
    fun `openApp reports failure when the resolved launcher binary is absent`() {
        // A name that maps to the `else -> listOf(listOf(appName))` branch and is
        // guaranteed not to exist on PATH, so ProcessBuilder.start() throws
        // immediately (no real process is ever created) and the loop exhausts.
        val absentApp = "jarvis-nonexistent-app-${UUID.randomUUID()}"

        val result = service.openApp(absentApp)

        assertTrue(result.isFailure, "launching an absent binary must fail")
        assertNotNull(result.exceptionOrNull())
    }

    // ---- resumeMediaStatic: no-op guard when nothing was paused by us ----

    @Test
    fun `resumeMediaStatic is a no-op when we did not pause anything`() {
        // Fresh JVM: the pausedByUs guard defaults to false, so this must return
        // immediately without ever spawning `playerctl play`.
        assertDoesNotThrow { SystemControlService.resumeMediaStatic() }
    }

    // ---- checkDependencies: shape is stable (harmless `which` probes only) ----

    @Test
    fun `checkDependencies reports a boolean for every known utility`() {
        val deps = service.checkDependencies()

        val expectedKeys = setOf("wpctl", "pactl", "amixer", "playerctl", "xdotool", "notify-send")
        assertEquals(expectedKeys, deps.keys, "dependency map must expose exactly the known utilities")
        // Values are environment-dependent; assert only that each key maps to a Boolean.
        deps.values.forEach { assertNotNull(it) }
        // Sanity: an obviously-absent probe key is never present.
        assertFalse(deps.containsKey("definitely-not-a-tool"))
    }
}
