package org.jarvis.desktop.theme

import java.util.prefs.PreferenceChangeEvent
import java.util.prefs.PreferenceChangeListener
import java.util.prefs.Preferences

/**
 * Persists whether the "Stark Lab" cinematic accent theme is layered on top
 * of the base unified-shell stylesheet. Backed by [Preferences] like
 * [org.jarvis.desktop.config.PreferencesDesktopSettingsStore], with an
 * injectable node so tests don't touch the real user prefs tree.
 */
class ThemePreferenceStore(
    private val preferences: Preferences = Preferences.userRoot().node("/org/jarvis/desktop/settings")
) {
    fun isStarkLabEnabled(): Boolean = preferences.getBoolean(KEY_STARK_LAB_THEME, false)

    fun setStarkLabEnabled(enabled: Boolean) {
        preferences.putBoolean(KEY_STARK_LAB_THEME, enabled)
        runCatching { preferences.flush() }
    }

    /** Returns the registered listener so callers can unregister it later. */
    fun addListener(listener: (Boolean) -> Unit): PreferenceChangeListener {
        val wrapped = PreferenceChangeListener { event: PreferenceChangeEvent ->
            if (event.key == KEY_STARK_LAB_THEME) {
                listener(event.newValue?.toBoolean() ?: false)
            }
        }
        preferences.addPreferenceChangeListener(wrapped)
        return wrapped
    }

    fun removeListener(listener: PreferenceChangeListener) {
        preferences.removePreferenceChangeListener(listener)
    }

    private companion object {
        const val KEY_STARK_LAB_THEME = "stark_lab_theme_enabled"
    }
}

/** App-wide singleton so Settings and the shell share one live preference. */
object ThemePreference {
    private val store = ThemePreferenceStore()

    fun isEnabled(): Boolean = store.isStarkLabEnabled()

    fun setEnabled(enabled: Boolean) = store.setStarkLabEnabled(enabled)

    fun addListener(listener: (Boolean) -> Unit): PreferenceChangeListener = store.addListener(listener)

    fun removeListener(listener: PreferenceChangeListener) = store.removeListener(listener)
}
