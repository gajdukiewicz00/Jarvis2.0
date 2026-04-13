package org.jarvis.desktop.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PorcupineCompatibilityTest {

    @Test
    fun `rewrites ppn library mismatch into explicit guidance`() {
        val message = """
            Initialization failed:
              [0] Keyword file (.ppn) file belongs to a different version of the library. File is `4.0.0` while library is `3.0.0`.
        """.trimIndent()

        assertEquals(
            "Bundled wake-word model is incompatible with the Porcupine runtime. " +
                "The current jarvis_ru.ppn requires Porcupine 4.x. " +
                "Update ai.picovoice:porcupine-java or replace the keyword file with a matching version.",
            PorcupineCompatibility.describeInitializationFailure(message)
        )
    }

    @Test
    fun `passes through non-compatibility errors`() {
        val message = "AccessKey is invalid."

        assertEquals(message, PorcupineCompatibility.describeInitializationFailure(message))
    }

    @Test
    fun `returns null for blank errors`() {
        assertNull(PorcupineCompatibility.describeInitializationFailure("   "))
    }
}
