package org.jarvis.desktop.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exhaustively covers the pure presentation/decision helpers on [VoiceState]
 * for every enum constant: [VoiceState.getDisplayText], [VoiceState.getColorHex],
 * [VoiceState.isWakeWordDisabled] and [VoiceState.isMicMuted].
 */
class VoiceStateDisplayTest {

    @Test
    fun `getDisplayText returns a localized label for every state`() {
        assertEquals("Не активен", VoiceState.IDLE.getDisplayText())
        assertEquals("Скажите 'Jarvis'", VoiceState.LISTENING_WAKE_WORD.getDisplayText())
        assertEquals("Слушаю команду...", VoiceState.LISTENING.getDisplayText())
        assertEquals("Обрабатываю...", VoiceState.PROCESSING.getDisplayText())
        assertEquals("Джарвис отвечает...", VoiceState.TTS_PLAYBACK.getDisplayText())
        assertEquals("Пауза...", VoiceState.COOLDOWN.getDisplayText())
        assertEquals("Ошибка", VoiceState.ERROR.getDisplayText())
    }

    @Test
    fun `getColorHex returns a distinct color for every state`() {
        assertEquals("#808080", VoiceState.IDLE.getColorHex())
        assertEquals("#4A90E2", VoiceState.LISTENING_WAKE_WORD.getColorHex())
        assertEquals("#E74C3C", VoiceState.LISTENING.getColorHex())
        assertEquals("#F39C12", VoiceState.PROCESSING.getColorHex())
        assertEquals("#27AE60", VoiceState.TTS_PLAYBACK.getColorHex())
        assertEquals("#9B59B6", VoiceState.COOLDOWN.getColorHex())
        assertEquals("#C0392B", VoiceState.ERROR.getColorHex())

        // All colors are unique — guards against copy-paste regressions.
        val colors = VoiceState.entries.map { it.getColorHex() }
        assertEquals(colors.size, colors.toSet().size)
    }

    @Test
    fun `isWakeWordDisabled is true only while a command or response is in flight`() {
        assertFalse(VoiceState.IDLE.isWakeWordDisabled())
        assertFalse(VoiceState.LISTENING_WAKE_WORD.isWakeWordDisabled())
        assertFalse(VoiceState.ERROR.isWakeWordDisabled())

        assertTrue(VoiceState.LISTENING.isWakeWordDisabled())
        assertTrue(VoiceState.PROCESSING.isWakeWordDisabled())
        assertTrue(VoiceState.TTS_PLAYBACK.isWakeWordDisabled())
        assertTrue(VoiceState.COOLDOWN.isWakeWordDisabled())
    }

    @Test
    fun `isMicMuted is true when idle or during playback and cooldown`() {
        assertTrue(VoiceState.IDLE.isMicMuted())
        assertTrue(VoiceState.TTS_PLAYBACK.isMicMuted())
        assertTrue(VoiceState.COOLDOWN.isMicMuted())

        assertFalse(VoiceState.LISTENING_WAKE_WORD.isMicMuted())
        assertFalse(VoiceState.LISTENING.isMicMuted())
        assertFalse(VoiceState.PROCESSING.isMicMuted())
        assertFalse(VoiceState.ERROR.isMicMuted())
    }
}
