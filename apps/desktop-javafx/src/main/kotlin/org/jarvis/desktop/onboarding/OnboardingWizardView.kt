package org.jarvis.desktop.onboarding

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox

/**
 * First-run onboarding wizard. Rendered as a full-bleed scrim + centered
 * card so it can simply be stacked on top of the shell's content host
 * (itself a [javafx.scene.layout.StackPane]) without any changes to the
 * shell's layout structure.
 *
 * Self-contained: no [org.jarvis.desktop.api.ApiClient] / network calls, so
 * it is safe to construct headlessly.
 */
class OnboardingWizardView(
    private val onFinish: () -> Unit,
    onSkip: (() -> Unit)? = null,
    private val state: OnboardingWizardState = OnboardingWizardState()
) : StackPane() {

    private val effectiveOnSkip: () -> Unit = onSkip ?: onFinish

    private val progressLabel = Label().apply { styleClass += "onboarding-progress" }
    private val titleLabel = Label().apply { styleClass += "onboarding-step-title" }
    private val bodyLabel = Label().apply {
        styleClass += "onboarding-step-body"
        isWrapText = true
    }
    private val backButton = Button("Back").apply { styleClass += "shell-action-button" }
    private val nextButton = Button("Next").apply { styleClass += "shell-action-button" }
    private val skipButton = Button("Skip").apply { styleClass += "shell-action-button" }

    init {
        styleClass += "onboarding-scrim"

        val actions = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            children += skipButton
            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)
            children += spacer
            children += backButton
            children += nextButton
        }

        val card = VBox(16.0).apply {
            styleClass += "onboarding-card"
            maxWidth = 480.0
            padding = Insets(28.0)
            children.addAll(progressLabel, titleLabel, bodyLabel, actions)
        }

        children += card

        backButton.setOnAction { if (state.back()) render() }
        nextButton.setOnAction {
            if (state.isLastStep) onFinish() else {
                state.next()
                render()
            }
        }
        skipButton.setOnAction { effectiveOnSkip() }

        render()
    }

    private fun render() {
        progressLabel.text = state.progressLabel
        titleLabel.text = state.current.title
        bodyLabel.text = state.current.body
        backButton.isDisable = state.isFirstStep
        nextButton.text = if (state.isLastStep) "Get started" else "Next"
    }
}
