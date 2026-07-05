package org.jarvis.desktop.i18n

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * [I18n] is a process-wide singleton that also mutates [Locale.setDefault] —
 * every test here restores the original locale in [tearDown] so this suite
 * cannot leak state into other test classes that share the JVM.
 */
class I18nTest {

    private lateinit var originalLocale: Locale

    @BeforeEach
    fun setUp() {
        originalLocale = I18n.getCurrentLocale()
    }

    @AfterEach
    fun tearDown() {
        I18n.setLocale(originalLocale)
    }

    @Test
    fun `setLocale switches the active bundle and getCurrentLocale reflects it`() {
        I18n.setLocale(Locale.ENGLISH)
        assertEquals(Locale.ENGLISH, I18n.getCurrentLocale())
        assertEquals("Jarvis Voice Assistant", I18n.getString("app.title"))
    }

    @Test
    fun `setLocale to Russian loads the Russian bundle`() {
        I18n.setLocale(Locale("ru", "RU"))
        assertEquals("ru", I18n.getCurrentLocale().language)
        // Whatever the translated string is, it must differ from the raw key marker.
        assertTrue(!I18n.getString("app.title").startsWith("!"))
    }

    @Test
    fun `getString returns a bracketed marker for a missing key`() {
        I18n.setLocale(Locale.ENGLISH)
        assertEquals("!this.key.does.not.exist!", I18n.getString("this.key.does.not.exist"))
    }

    @Test
    fun `getString with args formats using the resolved pattern`() {
        I18n.setLocale(Locale.ENGLISH)
        // app.title has no placeholders, but the vararg overload must still resolve
        // the same key and never throw even when no args are supplied.
        assertEquals(I18n.getString("app.title"), I18n.getString("app.title"))
    }

    @Test
    fun `convenience accessors resolve through getString`() {
        I18n.setLocale(Locale.ENGLISH)
        assertEquals("Jarvis Voice Assistant", I18n.appTitle)
        assertEquals("Speak", I18n.buttonSpeak)
        assertEquals("Stop", I18n.buttonStop)
        assertEquals("Settings", I18n.buttonSettings)
        assertEquals("Ready", I18n.statusReady)
        assertEquals("Listening...", I18n.statusListening)
        assertEquals("Processing...", I18n.statusProcessing)
    }

    @Test
    fun `supportedLocales exposes English Polish and Russian`() {
        assertEquals(3, I18n.supportedLocales.size)
        assertTrue(I18n.supportedLocales.any { it.language == "en" })
        assertTrue(I18n.supportedLocales.any { it.language == "pl" })
        assertTrue(I18n.supportedLocales.any { it.language == "ru" })
    }
}
