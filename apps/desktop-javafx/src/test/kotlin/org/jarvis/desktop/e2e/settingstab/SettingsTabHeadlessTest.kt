package org.jarvis.desktop.e2e.settingstab

import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.TextField
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.ui.tabs.SettingsTab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless JavaFX coverage for the production [SettingsTab].
 *
 * [SettingsTab] builds a multi-section settings form (Configuration, Service
 * Status, Preferences, About, Account) and wires several handlers. These tests
 * construct the REAL tab on the FX thread and drive only the NON-MODAL,
 * NO-SIDE-EFFECT handlers:
 *
 *  - the "Pin API Gateway URL manually" checkbox, which toggles whether the
 *    gateway field is editable, and
 *  - the "Logout" button, which invokes the injected callback and flips the
 *    status label.
 *
 * DELIBERATELY NOT DRIVEN (documented, not overlooked):
 *  - "💾 Save": `saveSettings()` calls the `AppConfig` singleton, which persists
 *    to the user's real `Preferences.userRoot()` node. There is no injectable
 *    settings-store seam on `SettingsTab`, so firing it would mutate real,
 *    persistent OS-level user preferences — an unacceptable test side effect.
 *  - "🔍 Check All Services": `checkServiceStatus()` builds a
 *    `DesktopServiceHealthChecker` whose config provider defaults to
 *    `AppConfig::current` (NOT the injected ApiClient), so it performs real
 *    network probes to the resolved runtime endpoints with multi-second
 *    timeouts — non-deterministic and environment-dependent.
 *
 * Construction alone still exercises `init {}` + the private
 * `refreshResolvedConfig()` (labels, source/reason, field-disable state).
 */
class SettingsTabHeadlessTest {

    /** An [ApiClient] pointed at a dead port; SettingsTab does no network I/O at construction. */
    private fun deadApiClient(): ApiClient = ApiClient(
        configProvider = {
            ResolvedDesktopConfig(
                apiGatewayBaseUrl = "http://127.0.0.1:1",
                apiBaseUrl = "http://127.0.0.1:1/api/v1",
                voiceWebSocketUrl = "ws://127.0.0.1:1/ws/voice",
                pcControlWebSocketUrl = "ws://127.0.0.1:1/ws/pc-control",
                locale = Locale.ENGLISH,
                voiceLanguage = "en-US",
                apiGatewaySource = ConfigSource.MANUAL_PERSISTED_SETTINGS,
                apiGatewayReason = "settings tab headless test",
                usesManualEndpointOverride = true
            )
        }
    )

    private fun buildTab(onLogout: () -> Unit = {}): SettingsTab =
        E2eFx.onFx { SettingsTab(deadApiClient(), onLogout) }

    private fun contentOf(tab: SettingsTab): Node =
        E2eFx.onFx { requireNotNull(tab.tab.content) { "SettingsTab content was not built" } }

    @Test
    fun `settings tab renders all its sections and controls`() {
        val tab = buildTab()
        val root = contentOf(tab)
        E2eFx.onFx {
            // Section titles + field labels.
            for (label in listOf(
                "Settings", "Configuration", "Service Status", "Preferences", "About", "Account",
                "API Gateway URL:", "Language:", "Theme:", "Effective API client:",
                "Endpoint source:", "Endpoint decision:",
                "Jarvis 2.0 Desktop Client", "Voice Gateway", "NLP Service"
            )) {
                assertTrue(E2eFx.hasText(root, label), "expected section/label text: $label")
            }

            // Action buttons.
            val buttonLabels = E2eFx.findAll<Button>(root).map { it.text }
            assertTrue(buttonLabels.any { it?.contains("Save") == true }, "Save button present")
            assertTrue(buttonLabels.any { it?.contains("Check All Services") == true }, "Check button present")
            assertTrue(buttonLabels.any { it?.contains("Logout") == true }, "Logout button present")

            // Language combo is populated with the three offered locales.
            val combos = E2eFx.findAll<ComboBox<*>>(root)
            val languageCombo = combos.first { combo -> combo.items.any { it is SettingsTab.LanguageOption } }
            val optionNames = languageCombo.items.filterIsInstance<SettingsTab.LanguageOption>().map { it.displayName }
            assertTrue(optionNames.containsAll(listOf("English", "Polski", "Русский")), "all languages offered")

            // refreshResolvedConfig() populated the effective-endpoint value label (the
            // "Effective API client:" title is asserted above; its resolved value is set
            // asynchronously and its exact format is not pinned here).
        }
    }

    @Test
    fun `manual override checkbox toggles gateway field editability`() {
        val tab = buildTab()
        val root = contentOf(tab)
        E2eFx.onFx {
            val checkBox = E2eFx.find<CheckBox>(root) ?: error("manual-override checkbox not found")
            val gatewayField = E2eFx.find<TextField>(root) ?: error("gateway URL field not found")

            // CheckBox.fire() toggles selection AND runs the onAction handler (setting
            // isSelected directly would NOT run the handler). Fire once, then assert the
            // field's editability matches the resulting override state, and again to flip.
            checkBox.fire()
            val overrideOn = checkBox.isSelected
            assertEquals(!overrideOn, gatewayField.isDisable, "gateway field editable iff override pinned")

            checkBox.fire()
            assertEquals(overrideOn, gatewayField.isDisable, "gateway field editability flips with override")
        }
    }

    @Test
    fun `logout button invokes the callback and updates the status label`() {
        val loggedOut = AtomicBoolean(false)
        val tab = buildTab(onLogout = { loggedOut.set(true) })
        val root = contentOf(tab)
        E2eFx.onFx {
            val logoutBtn = E2eFx.findAll<Button>(root)
                .first { it.text?.contains("Logout") == true }
            logoutBtn.fire()
        }
        E2eFx.onFx {
            assertTrue(loggedOut.get(), "onLogout callback invoked")
            assertTrue(E2eFx.hasText(root, "Logging out..."), "status label reflects logout in progress")
        }
        assertEquals(true, loggedOut.get())
    }
}
