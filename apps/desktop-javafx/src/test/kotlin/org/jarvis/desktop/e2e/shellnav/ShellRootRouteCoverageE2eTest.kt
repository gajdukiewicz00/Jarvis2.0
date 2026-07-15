package org.jarvis.desktop.e2e.shellnav

import javafx.scene.Node
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.palette.CommandPaletteOverlay
import org.jarvis.desktop.shell.ShellRoot
import org.jarvis.desktop.shell.ShellRoute
import org.jarvis.desktop.theme.ThemePreference
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Deep branch coverage for [ShellRoot] beyond the happy-path navigation already
 * exercised in [ShellRootE2eTest]. Here we:
 *
 *  1. Walk the shell to EVERY visible route so [ShellRoot.routeView]'s big `when`
 *     (one arm per feature screen) and the [ShellRoot.scrollableDisplay] wrapper
 *     both execute. The VOICE and LIFE routes are intentionally skipped: their
 *     `onRouteActivated()` (fired by showRoute on navigation) touches audio
 *     hardware / long-lived panels that don't belong in a headless unit test.
 *  2. Open then close the command palette through the real top-bar command field
 *     (the only non-accelerator entry point), covering [ShellRoot]'s
 *     toggle/close palette branches and the lazily-built palette entry list.
 *  3. Toggle the global theme while the shell has no Scene attached, driving the
 *     theme listener into applyTheme's `scene ?: return` guard without hanging on
 *     a shown Stage.
 *
 * Every ShellRoot is shut down in a finally block to detach global listeners and
 * stop the background executors.
 */
class ShellRootRouteCoverageE2eTest {

    /** Routes whose views are unsafe to activate headlessly (hardware / heavy panels). */
    private val skippedRoutes = setOf(ShellRoute.VOICE, ShellRoute.LIFE)

    private fun center(shell: ShellRoot): Node = shell.center
    private fun topBar(shell: ShellRoot): Node = shell.top

    private fun buildShell(): ShellRoot = ShellRoot(onLogoutRequested = {})

    private fun navButtonOrNull(shell: ShellRoot, route: ShellRoute): ToggleButton? =
        E2eFx.findAll<ToggleButton>(shell).firstOrNull { it.text == route.navLabel }

    @Test
    fun `navigating to every visible route builds and shows that route's feature view`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        val shell = E2eFx.onFx { buildShell() }
        try {
            var visited = 0
            ShellRoute.entries.filterNot { it in skippedRoutes }.forEach { route ->
                val button = E2eFx.onFx { navButtonOrNull(shell, route) } ?: return@forEach
                // Skip the already-active route (the landing CONTROL_CENTER): firing its
                // toggle would deselect it because navigateTo is a no-op for the current route.
                if (E2eFx.onFx { button.isSelected }) return@forEach
                E2eFx.onFx { button.fire() }
                E2eFx.onFx {
                    // The center content host swapped to a non-empty routed view and the
                    // top bar title reflects the active route (visible reaction).
                    val host = center(shell) as javafx.scene.layout.StackPane
                    assertTrue(host.children.isNotEmpty(), "route ${route.name} should render content")
                    assertTrue(
                        E2eFx.hasText(topBar(shell), route.title),
                        "top bar title should update to ${route.title}"
                    )
                    assertTrue(button.isSelected, "the ${route.navLabel} toggle should be selected")
                }
                visited++
            }
            assertTrue(visited >= 10, "expected to walk through most feature routes, walked $visited")

            // Re-navigating to the landing route reuses the cached view instance.
            val ccButton = E2eFx.onFx { navButtonOrNull(shell, ShellRoute.CONTROL_CENTER) }
            assertNotNull(ccButton)
            E2eFx.onFx { ccButton!!.fire() }
            E2eFx.onFx {
                assertNotNull(
                    E2eFx.find<org.jarvis.desktop.features.controlcenter.ControlCenterView>(center(shell)),
                    "returning to Control Center should restore its cached view"
                )
            }
        } finally {
            E2eFx.onFx { shell.shutdown() }
        }
    }

    @Test
    fun `clicking the top bar command field opens the palette and clicking again closes it`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        val shell = E2eFx.onFx { buildShell() }
        try {
            val commandField = E2eFx.onFx { E2eFx.findAll<TextField>(topBar(shell)).first() }

            // Open: invoke the field's own mouse-click handler (it delegates straight to
            // ShellRoot.toggleCommandPalette and ignores the event object), avoiding any
            // skin-level mouse handling. This builds the lazy palette entry list and mounts
            // the overlay in the content host.
            E2eFx.onFx { commandField.onMouseClicked?.handle(clickEvent()) }
            E2eFx.onFx {
                assertNotNull(
                    E2eFx.find<CommandPaletteOverlay>(center(shell)),
                    "clicking the command field should open the command palette overlay"
                )
            }

            // Close: a second toggle removes the overlay again.
            E2eFx.onFx { commandField.onMouseClicked?.handle(clickEvent()) }
            E2eFx.onFx {
                assertNull(
                    E2eFx.find<CommandPaletteOverlay>(center(shell)),
                    "toggling again should close the command palette overlay"
                )
            }
        } finally {
            E2eFx.onFx { shell.shutdown() }
        }
    }

    @Test
    fun `toggling the theme with no scene attached is a safe no-op through applyTheme`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        val shell = E2eFx.onFx { buildShell() }
        val original = E2eFx.onFx { ThemePreference.isEnabled() }
        var threw = false
        try {
            // The shell registered a ThemePreference listener that hops to the FX thread
            // and calls applyTheme(enabled); with no Scene attached it must early-return.
            E2eFx.onFx {
                try {
                    ThemePreference.setEnabled(!original)
                    ThemePreference.setEnabled(original)
                } catch (t: Throwable) {
                    threw = true
                }
            }
            // Flush any queued Platform.runLater applyTheme callbacks.
            E2eFx.onFx { /* fence */ }
            assertFalse(threw, "toggling the theme without a scene must not throw")
        } finally {
            E2eFx.onFx {
                ThemePreference.setEnabled(original)
                shell.shutdown()
            }
        }
    }

    private fun clickEvent(): MouseEvent = MouseEvent(
        MouseEvent.MOUSE_CLICKED,
        0.0, 0.0, 0.0, 0.0,
        MouseButton.PRIMARY, 1,
        false, false, false, false,
        true, false, false,
        false, false, false,
        null
    )
}
