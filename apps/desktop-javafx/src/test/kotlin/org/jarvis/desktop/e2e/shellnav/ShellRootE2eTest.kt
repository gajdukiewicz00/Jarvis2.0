package org.jarvis.desktop.e2e.shellnav

import javafx.scene.Node
import javafx.scene.control.ToggleButton
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.controlcenter.ControlCenterView
import org.jarvis.desktop.features.diagnostics.DiagnosticsView
import org.jarvis.desktop.features.settings.SettingsView
import org.jarvis.desktop.features.status.ServiceStatusView
import org.jarvis.desktop.shell.ShellRoot
import org.jarvis.desktop.shell.ShellRoute
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * True UI end-to-end journeys for the *real* [ShellRoot] — the unified desktop
 * shell BorderPane — rather than a hand-rolled navigation harness.
 *
 * Constructing [ShellRoot] boots the entire shell chrome: the [ShellTopBar]
 * (top), the [ShellNavPane] (left), and the routed content host (center). It
 * also wires the [ShellNavigator], starts the (daemon) runtime/service-status
 * pollers, and lands on the Control Center route. Each journey fires the real
 * navigation ToggleButton a user clicks and asserts the center content node
 * actually swaps to the selected feature View, that the nav toggle selection
 * follows, and that the top bar's route title updates.
 *
 * Only navigation ToggleButtons are fired here. No control inside a routed View
 * is fired, so no modal `showAndWait` path is ever entered (which would stall
 * the headless FX thread). Every ShellRoot instance is [ShellRoot.shutdown]-ed
 * in a finally block to detach the global AppConfig/Theme listeners and stop the
 * background executors it spins up.
 */
class ShellRootE2eTest {

    /** The center content host of the shell (a StackPane holding the routed view). */
    private fun center(shell: ShellRoot): Node = shell.center

    /** The top bar node of the shell. */
    private fun topBar(shell: ShellRoot): Node = shell.top

    /** Find the always-visible navigation ToggleButton for [route] by its label. */
    private fun navButton(shell: ShellRoot, route: ShellRoute): ToggleButton =
        E2eFx.findAll<ToggleButton>(shell).first { it.text == route.navLabel }

    private fun buildShell(): ShellRoot = ShellRoot(onLogoutRequested = {})

    @Test
    fun `shell root constructs and lands on the Control Center route`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        val shell = E2eFx.onFx { buildShell() }
        try {
            E2eFx.onFx {
                // Center content is the Control Center feature view immediately after
                // construction (navigator replays CONTROL_CENTER through showRoute).
                assertNotNull(
                    E2eFx.find<ControlCenterView>(center(shell)),
                    "landing content should be the ControlCenterView"
                )
                // The top bar route title reflects the active route.
                assertTrue(
                    E2eFx.hasText(topBar(shell), ShellRoute.CONTROL_CENTER.title),
                    "top bar should show the Control Center route title"
                )
                // The Control Center nav toggle is the selected one on landing.
                assertTrue(
                    navButton(shell, ShellRoute.CONTROL_CENTER).isSelected,
                    "Control Center nav toggle should be selected on landing"
                )
            }
        } finally {
            E2eFx.onFx { shell.shutdown() }
        }
    }

    @Test
    fun `firing nav buttons swaps the center content between routes`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        val shell = E2eFx.onFx { buildShell() }
        try {
            // Control Center -> Service Status.
            E2eFx.onFx { navButton(shell, ShellRoute.SERVICE_STATUS).fire() }
            E2eFx.onFx {
                assertNotNull(
                    E2eFx.find<ServiceStatusView>(center(shell)),
                    "center should hold the ServiceStatusView after navigating"
                )
                assertNull(
                    E2eFx.find<ControlCenterView>(center(shell)),
                    "the Control Center view should have been swapped out of the center"
                )
                assertTrue(
                    navButton(shell, ShellRoute.SERVICE_STATUS).isSelected,
                    "the Service Status toggle should be selected"
                )
                assertFalse(
                    navButton(shell, ShellRoute.CONTROL_CENTER).isSelected,
                    "the Control Center toggle should no longer be selected"
                )
                assertTrue(
                    E2eFx.hasText(topBar(shell), ShellRoute.SERVICE_STATUS.title),
                    "top bar title should update to the Service Status route"
                )
            }

            // Service Status -> Settings.
            E2eFx.onFx { navButton(shell, ShellRoute.SETTINGS).fire() }
            E2eFx.onFx {
                assertNotNull(
                    E2eFx.find<SettingsView>(center(shell)),
                    "center should hold the SettingsView after navigating"
                )
                assertNull(E2eFx.find<ServiceStatusView>(center(shell)))
            }

            // Settings -> Diagnostics.
            E2eFx.onFx { navButton(shell, ShellRoute.DIAGNOSTICS).fire() }
            E2eFx.onFx {
                assertNotNull(
                    E2eFx.find<DiagnosticsView>(center(shell)),
                    "center should hold the DiagnosticsView after navigating"
                )
                assertNull(E2eFx.find<SettingsView>(center(shell)))
            }

            // Diagnostics -> back to Control Center.
            E2eFx.onFx { navButton(shell, ShellRoute.CONTROL_CENTER).fire() }
            E2eFx.onFx {
                assertNotNull(
                    E2eFx.find<ControlCenterView>(center(shell)),
                    "navigating back should restore the Control Center view"
                )
                assertNull(E2eFx.find<DiagnosticsView>(center(shell)))
                assertTrue(navButton(shell, ShellRoute.CONTROL_CENTER).isSelected)
            }
        } finally {
            E2eFx.onFx { shell.shutdown() }
        }
    }

    @Test
    fun `re-navigating to a route reuses the same cached view instance`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        val shell = E2eFx.onFx { buildShell() }
        try {
            E2eFx.onFx { navButton(shell, ShellRoute.SERVICE_STATUS).fire() }
            val first = E2eFx.onFx { E2eFx.find<ServiceStatusView>(center(shell)) }
            assertNotNull(first, "Service Status view should be present after first navigation")

            // Navigate away and back — ShellRoot caches route views per route.
            E2eFx.onFx { navButton(shell, ShellRoute.SETTINGS).fire() }
            E2eFx.onFx { navButton(shell, ShellRoute.SERVICE_STATUS).fire() }

            val second = E2eFx.onFx { E2eFx.find<ServiceStatusView>(center(shell)) }
            assertSame(
                first,
                second,
                "returning to a route should reuse the cached view instance, not rebuild it"
            )
        } finally {
            E2eFx.onFx { shell.shutdown() }
        }
    }

    @Test
    fun `shutdown is idempotent and detaches navigation so the center stops swapping`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        val shell = E2eFx.onFx { buildShell() }
        var threw = false
        E2eFx.onFx {
            try {
                shell.shutdown()
                // Second call must early-return via the shutdownRequested guard.
                shell.shutdown()
            } catch (t: Throwable) {
                threw = true
            }
        }
        assertFalse(threw, "shutdown must be safe to call twice")

        // After shutdown the route listener is removed, so firing a nav button no
        // longer swaps the center content.
        E2eFx.onFx {
            val before = E2eFx.find<ControlCenterView>(center(shell))
            navButton(shell, ShellRoute.SERVICE_STATUS).fire()
            val after = center(shell)
            assertNull(
                E2eFx.find<ServiceStatusView>(after),
                "a disposed shell should not route to a new view"
            )
            // Control Center content that was last shown is still in place.
            assertSame(before, E2eFx.find<ControlCenterView>(after))
        }
    }
}
