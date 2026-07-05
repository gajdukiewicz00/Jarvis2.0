package org.jarvis.desktop.onboarding

data class OnboardingStep(
    val title: String,
    val body: String
)

/**
 * Pure step-sequencing state for the first-run onboarding wizard.
 * No JavaFX / IO dependencies so it stays trivially unit-testable.
 */
class OnboardingWizardState(
    val steps: List<OnboardingStep> = DEFAULT_STEPS
) {
    init {
        require(steps.isNotEmpty()) { "Onboarding requires at least one step" }
    }

    var currentIndex: Int = 0
        private set

    val current: OnboardingStep
        get() = steps[currentIndex]

    val isFirstStep: Boolean
        get() = currentIndex == 0

    val isLastStep: Boolean
        get() = currentIndex == steps.lastIndex

    val progressLabel: String
        get() = "Step ${currentIndex + 1} of ${steps.size}"

    /** Advances to the next step. Returns false (no-op) if already on the last step. */
    fun next(): Boolean {
        if (isLastStep) return false
        currentIndex++
        return true
    }

    /** Moves back to the previous step. Returns false (no-op) if already on the first step. */
    fun back(): Boolean {
        if (isFirstStep) return false
        currentIndex--
        return true
    }

    companion object {
        val DEFAULT_STEPS = listOf(
            OnboardingStep(
                title = "Welcome to Jarvis",
                body = "Jarvis is your local, on-device assistant: voice, brain chat, memory, " +
                    "finance, and PC control, all in one unified shell."
            ),
            OnboardingStep(
                title = "Find your way around",
                body = "Use the left navigation to reach every feature, or press Ctrl+K anytime " +
                    "to open the command palette and jump straight to a screen or action."
            ),
            OnboardingStep(
                title = "Stay in control",
                body = "The Panic button in the top bar halts every automated action instantly. " +
                    "Service Status shows model and service health at a glance."
            ),
            OnboardingStep(
                title = "You're ready",
                body = "That's the tour. Revisit this checklist any time from the Control Center."
            )
        )
    }
}
