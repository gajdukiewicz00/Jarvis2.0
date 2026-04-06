package org.jarvis.desktop.config

object PorcupineCompatibility {
    private const val VERSION_MISMATCH_MARKER =
        "Keyword file (.ppn) file belongs to a different version of the library"

    fun describeInitializationFailure(message: String?): String? {
        val trimmed = message?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return null
        }

        if (trimmed.contains(VERSION_MISMATCH_MARKER, ignoreCase = true)) {
            return "Bundled wake-word model is incompatible with the Porcupine runtime. " +
                "The current jarvis_ru.ppn requires Porcupine 4.x. " +
                "Update ai.picovoice:porcupine-java or replace the keyword file with a matching version."
        }

        return trimmed
    }
}
