package org.jarvis.desktop.onboarding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OnboardingWizardStateTest {

    private val steps = listOf(
        OnboardingStep("First", "First body"),
        OnboardingStep("Second", "Second body"),
        OnboardingStep("Third", "Third body")
    )

    @Test
    fun `starts on the first step`() {
        val state = OnboardingWizardState(steps)

        assertEquals(0, state.currentIndex)
        assertEquals("First", state.current.title)
        assertTrue(state.isFirstStep)
        assertFalse(state.isLastStep)
        assertEquals("Step 1 of 3", state.progressLabel)
    }

    @Test
    fun `next advances to the following step and returns true`() {
        val state = OnboardingWizardState(steps)

        assertTrue(state.next())

        assertEquals(1, state.currentIndex)
        assertEquals("Second", state.current.title)
        assertFalse(state.isFirstStep)
        assertFalse(state.isLastStep)
    }

    @Test
    fun `next is a no-op returning false once on the last step`() {
        val state = OnboardingWizardState(steps)
        state.next()
        state.next()

        assertTrue(state.isLastStep)
        assertFalse(state.next())
        assertEquals(2, state.currentIndex)
    }

    @Test
    fun `back is a no-op returning false on the first step`() {
        val state = OnboardingWizardState(steps)

        assertFalse(state.back())
        assertEquals(0, state.currentIndex)
    }

    @Test
    fun `back returns to the previous step`() {
        val state = OnboardingWizardState(steps)
        state.next()

        assertTrue(state.back())

        assertEquals(0, state.currentIndex)
        assertEquals("First", state.current.title)
    }

    @Test
    fun `rejects an empty step list`() {
        assertThrows(IllegalArgumentException::class.java) {
            OnboardingWizardState(emptyList())
        }
    }

    @Test
    fun `default steps are non-empty and start at the welcome step`() {
        val state = OnboardingWizardState()

        assertTrue(state.steps.isNotEmpty())
        assertEquals(OnboardingWizardState.DEFAULT_STEPS.first().title, state.current.title)
    }
}
