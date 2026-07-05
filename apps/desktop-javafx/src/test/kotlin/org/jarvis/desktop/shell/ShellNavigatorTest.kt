package org.jarvis.desktop.shell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShellNavigatorTest {

    @Test
    fun `defaults to HOME when no initial route is supplied`() {
        val navigator = ShellNavigator()
        assertEquals(ShellRoute.HOME, navigator.currentRoute())
    }

    @Test
    fun `navigateTo updates currentRoute and notifies listeners`() {
        val navigator = ShellNavigator(initialRoute = ShellRoute.HOME)
        val seen = mutableListOf<ShellRoute>()
        navigator.addListener { seen += it } // fires immediately with current route too

        navigator.navigateTo(ShellRoute.FINANCE)

        assertEquals(ShellRoute.FINANCE, navigator.currentRoute())
        assertEquals(listOf(ShellRoute.HOME, ShellRoute.FINANCE), seen)
    }

    @Test
    fun `navigateTo to the same route is a no-op and does not notify`() {
        val navigator = ShellNavigator(initialRoute = ShellRoute.VOICE)
        var notifications = 0
        navigator.addListener { notifications++ } // initial call counts as 1

        navigator.navigateTo(ShellRoute.VOICE)

        assertEquals(1, notifications)
        assertEquals(ShellRoute.VOICE, navigator.currentRoute())
    }

    @Test
    fun `addListener immediately replays the current route`() {
        val navigator = ShellNavigator(initialRoute = ShellRoute.SETTINGS)
        var replayed: ShellRoute? = null
        navigator.addListener { replayed = it }
        assertEquals(ShellRoute.SETTINGS, replayed)
    }

    @Test
    fun `removeListener stops further notifications`() {
        val navigator = ShellNavigator()
        val seen = mutableListOf<ShellRoute>()
        val listener: (ShellRoute) -> Unit = { seen += it }
        navigator.addListener(listener)
        navigator.removeListener(listener)

        navigator.navigateTo(ShellRoute.MEMORY)

        assertEquals(listOf(ShellRoute.HOME), seen, "only the initial replay should be recorded")
    }

    @Test
    fun `multiple listeners all receive navigation events`() {
        val navigator = ShellNavigator()
        var firstCalls = 0
        var secondCalls = 0
        navigator.addListener { firstCalls++ }
        navigator.addListener { secondCalls++ }

        navigator.navigateTo(ShellRoute.PLANNER)

        assertTrue(firstCalls >= 2 && secondCalls >= 2, "each listener should see the initial replay plus the navigation")
        assertFalse(navigator.currentRoute() == ShellRoute.HOME)
    }
}
