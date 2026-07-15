package org.jarvis.desktop.app

import javafx.stage.Stage
import org.jarvis.desktop.controller.LoginController
import org.jarvis.desktop.e2e.E2eFx
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Coverage for [DesktopShellHost]'s non-launch lifecycle helpers — the parts
 * that don't show a Stage or load the login FXML (which would need a real,
 * shown window and would hang headlessly). We drive [DesktopShellHost.attach]
 * caching, [DesktopShellHost.shutdown] with no shell mounted, and
 * [DesktopShellHost.close] on a never-shown stage.
 *
 * A [Stage] must be created on the FX thread, so every step runs through
 * [E2eFx.onFx].
 */
class DesktopShellHostTest {

    private companion object {
        const val HOST_KEY = "jarvis.desktop.shell.host"
    }

    @Test
    fun `attach caches the host under the stage and returns the same instance on re-attach`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        E2eFx.onFx {
            val stage = Stage()
            val first = DesktopShellHost.attach(stage)
            val second = DesktopShellHost.attach(stage)
            assertSame(first, second, "re-attaching to the same stage must return the cached host")
            assertSame(first, stage.properties[HOST_KEY], "the host is stored under the stage property key")
        }
    }

    @Test
    fun `shutdown with no mounted shell clears the login success handler and does not throw`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        E2eFx.onFx {
            LoginController.loginSuccessHandler = { /* stale handler from a prior session */ }
            val stage = Stage()
            val host = DesktopShellHost.attach(stage)
            host.shutdown() // shellRoot is null -> safe; must still null the global handler
            assertNull(LoginController.loginSuccessHandler, "shutdown clears the login success handler")
        }
    }

    @Test
    fun `close on a never-shown stage removes the host key and invokes onClosed`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        E2eFx.onFx {
            var closed = false
            val stage = Stage()
            val host = DesktopShellHost.attach(stage, onClosed = { closed = true })
            assertSame(host, stage.properties[HOST_KEY])

            host.close() // not showing -> else branch: remove key + onClosed callback

            assertTrue(closed, "onClosed should fire when closing a non-showing stage")
            assertFalse(stage.properties.containsKey(HOST_KEY), "the host key should be removed on close")
            // With the key gone, a fresh attach mints a brand new host.
            assertNotSame(host, DesktopShellHost.attach(stage), "attach after close returns a new host")
        }
    }
}
