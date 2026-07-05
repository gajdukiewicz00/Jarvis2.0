package org.jarvis.desktop.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

class VoiceRecognitionLanguageTest {

    @Test
    fun `resolve honors explicit JARVIS_VOICE_LANGUAGE env override`() {
        val language = VoiceRecognitionLanguage.resolve(
            environment = mapOf("JARVIS_VOICE_LANGUAGE" to "en-us"),
            settings = DesktopSettings(),
            locale = Locale.ENGLISH
        )
        assertEquals(VoiceRecognitionLanguage.ENGLISH, language)
    }

    @Test
    fun `resolve falls back to settings localeTag when env is absent`() {
        val language = VoiceRecognitionLanguage.resolve(
            environment = emptyMap(),
            settings = DesktopSettings(localeTag = "en-GB"),
            locale = Locale.ENGLISH
        )
        assertEquals(VoiceRecognitionLanguage.ENGLISH, language)
    }

    @Test
    fun `resolve falls back to JARVIS_LOCALE env when settings localeTag is absent`() {
        val language = VoiceRecognitionLanguage.resolve(
            environment = mapOf("JARVIS_LOCALE" to "ru-RU"),
            settings = DesktopSettings(),
            locale = Locale.ENGLISH
        )
        assertEquals(VoiceRecognitionLanguage.RUSSIAN, language)
    }

    @Test
    fun `resolve defaults to Russian when nothing is configured`() {
        val language = VoiceRecognitionLanguage.resolve(
            environment = emptyMap(),
            settings = DesktopSettings(),
            locale = Locale.ENGLISH
        )
        assertEquals(VoiceRecognitionLanguage.DEFAULT, language)
        assertEquals(VoiceRecognitionLanguage.RUSSIAN, language)
    }

    @Test
    fun `normalize maps underscores and case to canonical language tags`() {
        assertEquals(VoiceRecognitionLanguage.ENGLISH, VoiceRecognitionLanguage.normalize("en_US"))
        assertEquals(VoiceRecognitionLanguage.RUSSIAN, VoiceRecognitionLanguage.normalize("RU_ru"))
        assertEquals(VoiceRecognitionLanguage.DEFAULT, VoiceRecognitionLanguage.normalize("pl-PL"))
        assertEquals(VoiceRecognitionLanguage.DEFAULT, VoiceRecognitionLanguage.normalize(null))
        assertEquals(VoiceRecognitionLanguage.DEFAULT, VoiceRecognitionLanguage.normalize("   "))
    }
}
