package org.jarvis.desktop.onboarding

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class OnboardingMarkerTest {

    @Test
    fun `isComplete is false before markComplete is called`() {
        val dir = Files.createTempDirectory("onboarding-marker-test")
        val marker = OnboardingMarker(dir.resolve("nested").resolve("onboarding-complete"))

        assertFalse(marker.isComplete())
    }

    @Test
    fun `markComplete creates the marker file and any missing parent directories`() {
        val dir = Files.createTempDirectory("onboarding-marker-test")
        val markerPath = dir.resolve("nested").resolve("onboarding-complete")
        val marker = OnboardingMarker(markerPath)

        marker.markComplete()

        assertTrue(marker.isComplete())
        assertTrue(Files.exists(markerPath))
    }

    @Test
    fun `markComplete is idempotent when called twice`() {
        val dir = Files.createTempDirectory("onboarding-marker-test")
        val marker = OnboardingMarker(dir.resolve("onboarding-complete"))

        marker.markComplete()
        marker.markComplete()

        assertTrue(marker.isComplete())
    }

    @Test
    fun `reset removes the marker so isComplete becomes false again`() {
        val dir = Files.createTempDirectory("onboarding-marker-test")
        val marker = OnboardingMarker(dir.resolve("onboarding-complete"))
        marker.markComplete()

        marker.reset()

        assertFalse(marker.isComplete())
    }
}
