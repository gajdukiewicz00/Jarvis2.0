package org.jarvis.desktop.features.voice

import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import org.jarvis.desktop.features.common.ShellPanelSupport
import org.jarvis.desktop.features.status.StatusLevel

/**
 * Compact status row for the Voice view: connection + mic/STT/TTS readiness
 * rendered from a single [VoiceChannelStatus] snapshot.
 *
 * Reuses the shared `shell-status-pill` / `shell-status-tone-*` classes via
 * [ShellPanelSupport] — the same building blocks Service Status, Planner, and
 * Smart Home already use — instead of inventing its own colors. Every chip's
 * label and tone come straight from [StatusLevel], so this row renders
 * consistently with the rest of the shell in both themes and can never show
 * "connected" next to an unrelated ad-hoc "degraded" label.
 */
class VoiceStatusRow : HBox(10.0) {

    private val headlineLabel = Label().apply {
        styleClass += "shell-section-subtitle"
        isWrapText = true
    }
    private val connectionChip = ShellPanelSupport.statusPill("Connection")
    private val micChip = ShellPanelSupport.statusPill("Mic")
    private val sttChip = ShellPanelSupport.statusPill("STT")
    private val ttsChip = ShellPanelSupport.statusPill("TTS")

    init {
        alignment = Pos.CENTER_LEFT
        styleClass += "voice-status-row"
        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        children.addAll(headlineLabel, spacer, connectionChip, micChip, sttChip, ttsChip)
        render(VoiceChannelStatusMapper.unknown())
    }

    fun render(status: VoiceChannelStatus) {
        headlineLabel.text = status.headline
        applyChip(connectionChip, "Connection", status.connection)
        applyChip(micChip, "Mic", status.mic)
        applyChip(sttChip, "STT", status.stt)
        applyChip(ttsChip, "TTS", status.tts)
    }

    private fun applyChip(chip: Label, name: String, level: StatusLevel) {
        chip.text = "$name: ${level.label}"
        ShellPanelSupport.applyTone(chip, level.toneStyleClass)
    }
}
