package org.jarvis.desktop.shell

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

class ShellRouteContentTest {

    private class NoopRouteContent : ShellRouteContent

    @Test
    fun `default lifecycle hooks are no-ops and never throw`() {
        val content = NoopRouteContent()
        assertDoesNotThrow { content.onRouteActivated() }
        assertDoesNotThrow { content.onRouteDeactivated() }
        assertDoesNotThrow { content.onShellShutdown() }
    }
}
