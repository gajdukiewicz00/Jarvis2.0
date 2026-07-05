package org.jarvis.desktop.shell

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShellRouteTest {

    @Test
    fun `visibleRoutes hides AI when both llm and memory are disabled`() {
        val routes = ShellRoute.visibleRoutes(runtimeMode = "local", llmEnabled = false, memoryEnabled = false)
        assertFalse(routes.contains(ShellRoute.AI))
        assertTrue(routes.contains(ShellRoute.HOME))
        assertEqualsAllNonAiRoutesPresent(routes)
    }

    @Test
    fun `visibleRoutes shows AI when llm is enabled`() {
        val routes = ShellRoute.visibleRoutes(runtimeMode = "k8s", llmEnabled = true, memoryEnabled = false)
        assertTrue(routes.contains(ShellRoute.AI))
    }

    @Test
    fun `visibleRoutes shows AI when memory is enabled even without llm`() {
        val routes = ShellRoute.visibleRoutes(runtimeMode = "k8s", llmEnabled = false, memoryEnabled = true)
        assertTrue(routes.contains(ShellRoute.AI))
    }

    private fun assertEqualsAllNonAiRoutesPresent(routes: List<ShellRoute>) {
        val expected = ShellRoute.entries.filter { it != ShellRoute.AI }
        assertTrue(routes.containsAll(expected))
        assertTrue(routes.size == expected.size)
    }
}
