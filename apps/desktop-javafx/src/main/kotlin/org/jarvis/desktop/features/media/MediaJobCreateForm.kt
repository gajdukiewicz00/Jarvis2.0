package org.jarvis.desktop.features.media

import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import org.jarvis.desktop.features.common.ShellPanelSupport

/** The kinds of media work the Create Job form can submit from the desktop client. */
enum class MediaJobKind(private val label: String) {
    PROBE("Probe (inspect streams)"),
    RUSSIAN_SUBTITLES("Russian Subtitles"),
    RUSSIAN_DUB_AUDIO("Russian Dub (audio)"),
    MUX("Mux (combine into output)");

    override fun toString(): String = label
}

/** A validated submission from [MediaJobCreateForm], one variant per [MediaJobKind]. */
sealed interface MediaCreateJobRequest {
    val sourcePath: String

    data class Probe(
        override val sourcePath: String,
        val preferredLanguage: String?,
        val overrideAudioIndex: Int?
    ) : MediaCreateJobRequest

    data class Subtitles(override val sourcePath: String) : MediaCreateJobRequest

    data class Dub(
        override val sourcePath: String,
        val voiceProfileMode: String,
        val voiceId: String?,
        val consentConfirmed: Boolean
    ) : MediaCreateJobRequest

    data class Mux(
        override val sourcePath: String,
        val subtitleFile: String?,
        val dubAudioFile: String?,
        val outputName: String?
    ) : MediaCreateJobRequest
}

/**
 * "Create Job" form embedded at the top of the Media Jobs panel.
 *
 * Purely presentational: validates its own fields (a source path is always
 * required; a user-owned dub voice additionally requires explicit consent)
 * and hands a [MediaCreateJobRequest] to [onSubmit]. It performs no network
 * I/O and knows nothing about job status, threading, or the read model —
 * [MediaJobsView] owns submission, busy-state, and error reporting.
 */
class MediaJobCreateForm(
    private val onSubmit: (MediaCreateJobRequest) -> Unit
) : VBox(12.0) {

    companion object {
        const val DEFAULT_VOICE_MODE = "neutral"
        const val USER_OWNED_VOICE_MODE = "user_owned"
    }

    internal val jobTypeCombo = ComboBox<MediaJobKind>().apply {
        items.addAll(MediaJobKind.entries)
        value = MediaJobKind.PROBE
    }

    internal val sourcePathField = TextField().apply {
        promptText = promptFor(MediaJobKind.PROBE)
    }
    private val browseButton = Button("Browse…").apply {
        styleClass += "shell-action-button"
        setOnAction { browseFor(sourcePathField) }
    }

    // Probe-only fields.
    internal val preferredLanguageField = TextField().apply { promptText = "Preferred language (optional, e.g. en)" }
    internal val overrideAudioIndexField = TextField().apply { promptText = "Override audio stream index (optional)" }
    private val probeFieldsRow = HBox(8.0).apply {
        alignment = Pos.CENTER_LEFT
        children.addAll(preferredLanguageField, overrideAudioIndexField)
    }

    // Russian Dub-only fields.
    internal val voiceModeCombo = ComboBox<String>().apply {
        items.addAll(DEFAULT_VOICE_MODE, USER_OWNED_VOICE_MODE)
        value = DEFAULT_VOICE_MODE
    }
    internal val voiceIdField = TextField().apply { promptText = "Voice id (required for user_owned)" }
    internal val consentCheckBox = CheckBox("I confirm this voice profile is mine to use")
    private val dubFieldsRow = VBox(6.0).apply {
        children.addAll(
            HBox(8.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(Label("Voice profile:").apply { styleClass += "shell-section-subtitle" }, voiceModeCombo, voiceIdField)
            },
            consentCheckBox
        )
    }

    // Mux-only fields.
    internal val subtitleFileField = TextField().apply { promptText = "Russian subtitle artifact path (optional, .srt)" }
    internal val dubAudioFileField = TextField().apply { promptText = "Russian dub-audio artifact path (optional)" }
    internal val outputNameField = TextField().apply { promptText = "Output filename (optional, defaults to output.mkv)" }
    private val muxFieldsRow = VBox(6.0).apply {
        children.addAll(subtitleFileField, dubAudioFileField, outputNameField)
    }

    internal val validationLabel = Label("").apply {
        styleClass += "shell-status-tone-error"
        isWrapText = true
        isVisible = false
        isManaged = false
    }

    internal val startButton = Button("Start").apply {
        styleClass += "shell-action-button"
        setOnAction { submit() }
    }

    private val formFields = VBox(10.0)

    init {
        styleClass += "shell-section-card"
        children += HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += ShellPanelSupport.sectionTitle("Create Job")
        }
        children += ShellPanelSupport.sectionSubtitle(
            "Submit a job to media-service: probe a file's streams, or generate Russian subtitles, a Russian " +
                "dub track, or a muxed output from existing artifacts."
        )

        formFields.children.addAll(
            HBox(8.0).apply {
                alignment = Pos.CENTER_LEFT
                children += Label("Job type:").apply { styleClass += "shell-section-subtitle" }
                children += jobTypeCombo
            },
            HBox(8.0).apply {
                alignment = Pos.CENTER_LEFT
                HBox.setHgrow(sourcePathField, Priority.ALWAYS)
                sourcePathField.maxWidth = Double.MAX_VALUE
                children.addAll(sourcePathField, browseButton)
            },
            probeFieldsRow,
            dubFieldsRow,
            muxFieldsRow,
            validationLabel,
            HBox(12.0).apply {
                alignment = Pos.CENTER_RIGHT
                val spacer = Region()
                HBox.setHgrow(spacer, Priority.ALWAYS)
                children.addAll(spacer, startButton)
            }
        )
        children += formFields

        jobTypeCombo.setOnAction { updateVisibleFieldsFor(jobTypeCombo.value ?: MediaJobKind.PROBE) }
        updateVisibleFieldsFor(MediaJobKind.PROBE)
    }

    /** Disables every input and the Start button while a submission is in flight. */
    fun setBusy(busy: Boolean) {
        formFields.isDisable = busy
    }

    private fun updateVisibleFieldsFor(kind: MediaJobKind) {
        probeFieldsRow.isVisible = kind == MediaJobKind.PROBE
        probeFieldsRow.isManaged = probeFieldsRow.isVisible
        dubFieldsRow.isVisible = kind == MediaJobKind.RUSSIAN_DUB_AUDIO
        dubFieldsRow.isManaged = dubFieldsRow.isVisible
        muxFieldsRow.isVisible = kind == MediaJobKind.MUX
        muxFieldsRow.isManaged = muxFieldsRow.isVisible
        sourcePathField.promptText = promptFor(kind)
        clearValidationError()
    }

    private fun promptFor(kind: MediaJobKind): String = when (kind) {
        MediaJobKind.PROBE -> "Media file path to probe"
        MediaJobKind.RUSSIAN_SUBTITLES -> "Transcript artifact path (transcript.json)"
        MediaJobKind.RUSSIAN_DUB_AUDIO -> "Russian transcript artifact path"
        MediaJobKind.MUX -> "Original media file path"
    }

    private fun browseFor(field: TextField) {
        val chooser = FileChooser().apply { title = "Select a file" }
        val selected = chooser.showOpenDialog(scene?.window) ?: return
        field.text = selected.absolutePath
    }

    private fun submit() {
        val sourcePath = sourcePathField.text?.trim().orEmpty()
        if (sourcePath.isBlank()) {
            showValidationError("Enter a source path first.")
            return
        }

        val request: MediaCreateJobRequest = when (jobTypeCombo.value ?: MediaJobKind.PROBE) {
            MediaJobKind.PROBE -> MediaCreateJobRequest.Probe(
                sourcePath = sourcePath,
                preferredLanguage = preferredLanguageField.text?.trim()?.takeIf { it.isNotBlank() },
                overrideAudioIndex = overrideAudioIndexField.text?.trim()?.toIntOrNull()
            )
            MediaJobKind.RUSSIAN_SUBTITLES -> MediaCreateJobRequest.Subtitles(sourcePath)
            MediaJobKind.RUSSIAN_DUB_AUDIO -> {
                val mode = voiceModeCombo.value ?: DEFAULT_VOICE_MODE
                if (mode == USER_OWNED_VOICE_MODE && !consentCheckBox.isSelected) {
                    showValidationError("Consent is required for a user-owned voice profile.")
                    return
                }
                MediaCreateJobRequest.Dub(
                    sourcePath = sourcePath,
                    voiceProfileMode = mode,
                    voiceId = voiceIdField.text?.trim()?.takeIf { it.isNotBlank() },
                    consentConfirmed = consentCheckBox.isSelected
                )
            }
            MediaJobKind.MUX -> MediaCreateJobRequest.Mux(
                sourcePath = sourcePath,
                subtitleFile = subtitleFileField.text?.trim()?.takeIf { it.isNotBlank() },
                dubAudioFile = dubAudioFileField.text?.trim()?.takeIf { it.isNotBlank() },
                outputName = outputNameField.text?.trim()?.takeIf { it.isNotBlank() }
            )
        }

        clearValidationError()
        onSubmit(request)
    }

    private fun showValidationError(message: String) {
        validationLabel.text = message
        validationLabel.isVisible = true
        validationLabel.isManaged = true
    }

    private fun clearValidationError() {
        validationLabel.text = ""
        validationLabel.isVisible = false
        validationLabel.isManaged = false
    }
}
