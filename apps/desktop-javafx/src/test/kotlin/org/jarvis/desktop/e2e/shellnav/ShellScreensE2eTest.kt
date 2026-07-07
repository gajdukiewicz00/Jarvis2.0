package org.jarvis.desktop.e2e.shellnav

import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.StackPane
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.settings.SettingsView
import org.jarvis.desktop.onboarding.OnboardingWizardView
import org.jarvis.desktop.palette.CommandPaletteAction
import org.jarvis.desktop.palette.CommandPaletteOverlay
import org.jarvis.desktop.palette.PaletteEntry
import org.jarvis.desktop.theme.ThemePreference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * True UI end-to-end journeys for a few shell screens that own their own scene
 * graph: Settings (persist a toggle), the onboarding wizard (advance steps), and
 * the command palette (open + filter + activate). The Settings/onboarding/palette
 * surfaces are documented as network-free, so the assertion surface here is the
 * visible scene graph plus real persisted state — see per-test notes.
 */
class ShellScreensE2eTest {

    // ---- SettingsView: renders + persists the Stark Lab theme toggle ----------

    @Test
    fun `settings stark lab checkbox persists the theme preference and reflects it in the control`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        val server = MockWebServer() // only used to build a real ApiClient; no requests are made
        server.start()
        val originalTheme = E2eFx.onFx { ThemePreference.isEnabled() }
        val view = E2eFx.onFx {
            SettingsView(E2eFx.apiClientFor(server), onLogout = {}).also { Scene(StackPane(it)) }
        }
        try {
            val checkBox = E2eFx.onFx {
                E2eFx.findAll<CheckBox>(view).first { it.text.contains("Stark Lab", ignoreCase = true) }
            }

            // Force a known starting state, then fire the real control the user clicks.
            E2eFx.onFx {
                checkBox.isSelected = false
                ThemePreference.setEnabled(false)
                checkBox.fire() // flips to selected AND runs the wired setOnAction handler
            }

            E2eFx.onFx {
                assertTrue(checkBox.isSelected, "checkbox should be checked after firing")
                assertTrue(ThemePreference.isEnabled(), "theme preference should be persisted as enabled")
            }

            // Toggle back off — the persisted setting follows the visible control.
            E2eFx.onFx { checkBox.fire() }
            E2eFx.onFx {
                assertFalse(checkBox.isSelected)
                assertFalse(ThemePreference.isEnabled(), "theme preference should be persisted as disabled")
            }
        } finally {
            E2eFx.onFx {
                ThemePreference.setEnabled(originalTheme) // restore global pref
                view.onShellShutdown() // detach the AppConfig listener + stop the worker
            }
            server.shutdown()
        }
    }

    // ---- OnboardingWizardView: stepping forward and back ----------------------

    @Test
    fun `onboarding wizard advances through steps and finishes on the last step`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        var finished = false
        val wizard = E2eFx.onFx {
            OnboardingWizardView(onFinish = { finished = true }).also { Scene(StackPane(it)) }
        }

        // First step renders "Step 1 of 4"; Back is disabled; Next says "Next".
        E2eFx.onFx {
            assertTrue(E2eFx.hasText(wizard, "Step 1 of 4"), "should start on the first step")
            assertTrue(E2eFx.hasText(wizard, "Welcome to Jarvis"))
            assertTrue(backButton(wizard).isDisable, "Back is disabled on the first step")
        }

        // Clicking Back on the first step is a no-op (edge case) — still step 1.
        E2eFx.onFx { backButton(wizard).fire() }
        E2eFx.onFx { assertTrue(E2eFx.hasText(wizard, "Step 1 of 4")) }

        // Advance to the last step by clicking Next repeatedly.
        E2eFx.onFx { nextButton(wizard).fire() }
        E2eFx.onFx { assertTrue(E2eFx.hasText(wizard, "Step 2 of 4"), "Next should advance to step 2") }

        E2eFx.onFx {
            nextButton(wizard).fire()
            nextButton(wizard).fire()
        }
        E2eFx.onFx {
            assertTrue(E2eFx.hasText(wizard, "Step 4 of 4"), "should reach the final step")
            assertEquals("Get started", nextButton(wizard).text, "final step CTA relabels to Get started")
            assertFalse(finished, "finish must not fire until the final Next is clicked")
        }

        // Final click invokes onFinish.
        E2eFx.onFx { nextButton(wizard).fire() }
        E2eFx.onFx { assertTrue(finished, "clicking Get started on the last step finishes onboarding") }
    }

    private fun backButton(wizard: OnboardingWizardView): Button =
        E2eFx.findAll<Button>(wizard).first { it.text == "Back" }

    private fun nextButton(wizard: OnboardingWizardView): Button =
        E2eFx.findAll<Button>(wizard).first { it.text == "Next" || it.text == "Get started" }

    // ---- CommandPaletteOverlay: opens, filters, and activates -----------------

    private fun paletteEntries(activated: MutableList<String>): List<CommandPaletteAction> = listOf(
        CommandPaletteAction(PaletteEntry("route-settings", "Settings", "Navigate")) { activated += "Settings" },
        CommandPaletteAction(PaletteEntry("route-memory", "Memory", "Navigate")) { activated += "Memory" },
        CommandPaletteAction(PaletteEntry("route-finance", "Finance", "Navigate")) { activated += "Finance" },
        CommandPaletteAction(PaletteEntry("action-panic", "Engage panic kill-switch", "Safety")) {
            activated += "Panic"
        }
    )

    @Test
    fun `command palette filters the list to the typed query and activating runs that action`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        val activated = mutableListOf<String>()
        var closed = false
        val entries = paletteEntries(activated)

        val overlay = E2eFx.onFx {
            CommandPaletteOverlay(entries = entries, onClose = { closed = true }).also { Scene(StackPane(it)) }
        }

        val field = E2eFx.onFx { E2eFx.find<TextField>(overlay)!! }
        val list = E2eFx.onFx { E2eFx.findAll<ListView<*>>(overlay).first() }

        // Opens showing every entry.
        E2eFx.onFx { assertEquals(entries.size, list.items.size, "palette starts with all entries") }

        // Typing narrows the visible results (real textProperty listener drives the filter).
        E2eFx.onFx { field.text = "finance" }
        E2eFx.onFx {
            assertEquals(1, list.items.size, "only the Finance entry should survive the filter")
            val remaining = (list.items.single() as CommandPaletteAction).entry.label
            assertEquals("Finance", remaining, "non-matching entries are filtered out")
        }

        // Pressing ENTER activates the first (only) match, then closes the overlay.
        E2eFx.onFx {
            field.fireEvent(
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false)
            )
        }
        E2eFx.onFx {
            assertEquals(listOf("Finance"), activated, "the matched action should have run")
            assertTrue(closed, "activating an entry closes the palette")
        }
    }

    @Test
    fun `command palette shows no matches for a query that matches nothing`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable")
        val activated = mutableListOf<String>()
        val entries = paletteEntries(activated)

        val overlay = E2eFx.onFx {
            CommandPaletteOverlay(entries = entries, onClose = {}).also { Scene(StackPane(it)) }
        }
        val field = E2eFx.onFx { E2eFx.find<TextField>(overlay)!! }
        val list = E2eFx.onFx { E2eFx.findAll<ListView<*>>(overlay).first() }

        E2eFx.onFx { field.text = "zzzz-no-such-command" }
        E2eFx.onFx {
            assertTrue(list.items.isEmpty(), "no entries should match")
            val placeholder = list.placeholder as? Label
            assertEquals("No matches", placeholder?.text, "the empty-state placeholder is shown")
            assertTrue(activated.isEmpty(), "nothing was activated")
        }
    }
}
