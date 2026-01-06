package org.jarvis.desktop.i18n

import java.util.*

/**
 * Internationalization support for multiple languages
 */
object I18n {
    private var currentLocale: Locale = Locale.getDefault()
    private var bundle: ResourceBundle = loadBundle(currentLocale)

    val supportedLocales = listOf(
        Locale.ENGLISH,
        Locale("pl", "PL"),  // Polish
        Locale("ru", "RU")   // Russian
    )

    fun setLocale(locale: Locale) {
        currentLocale = locale
        bundle = loadBundle(locale)
        Locale.setDefault(locale)
    }

    fun getCurrentLocale(): Locale = currentLocale

    fun getString(key: String): String {
        return try {
            bundle.getString(key)
        } catch (e: MissingResourceException) {
            "!$key!"
        }
    }

    fun getString(key: String, vararg args: Any): String {
        return try {
            String.format(bundle.getString(key), *args)
        } catch (e: MissingResourceException) {
            "!$key!"
        }
    }

    private fun loadBundle(locale: Locale): ResourceBundle {
        return ResourceBundle.getBundle("messages", locale)
    }

    // Convenience accessors
    val appTitle: String get() = getString("app.title")
    val buttonSpeak: String get() = getString("button.speak")
    val buttonStop: String get() = getString("button.stop")
    val buttonSettings: String get() = getString("button.settings")
    val statusReady: String get() = getString("app.status.ready")
    val statusListening: String get() = getString("app.status.listening")
    val statusProcessing: String get() = getString("app.status.processing")
}
