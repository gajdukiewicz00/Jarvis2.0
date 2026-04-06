package org.jarvis.desktop.config

import java.util.Locale

internal object VoiceRecognitionLanguage {
    const val RUSSIAN = "ru-RU"
    const val ENGLISH = "en-US"
    const val DEFAULT = RUSSIAN
    private const val LOCALE_ENV = "JARVIS_LOCALE"
    private const val VOICE_LANGUAGE_ENV = "JARVIS_VOICE_LANGUAGE"

    @Suppress("UNUSED_PARAMETER")
    fun resolve(environment: Map<String, String>, settings: DesktopSettings, locale: Locale): String {
        environment[VOICE_LANGUAGE_ENV]?.let { configured ->
            return normalize(configured)
        }

        val explicitLocaleTag = DesktopConfigResolver.normalizeLocaleTag(settings.localeTag)
            ?: DesktopConfigResolver.normalizeLocaleTag(environment[LOCALE_ENV])
        if (explicitLocaleTag != null) {
            return normalize(explicitLocaleTag)
        }

        return DEFAULT
    }

    fun normalize(languageTag: String?): String {
        val normalized = languageTag
            ?.trim()
            ?.replace('_', '-')
            ?.takeIf { it.isNotEmpty() }
            ?.lowercase(Locale.ROOT)
            ?: return DEFAULT

        return when {
            normalized.startsWith("en") -> ENGLISH
            normalized.startsWith("ru") -> RUSSIAN
            else -> DEFAULT
        }
    }
}
