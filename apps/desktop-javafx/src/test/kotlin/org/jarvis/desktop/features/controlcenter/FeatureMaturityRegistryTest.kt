package org.jarvis.desktop.features.controlcenter

import org.jarvis.desktop.shell.ShellRoute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class FeatureMaturityRegistryTest {

    @Test
    @DisplayName("verified end-to-end features are classified READY")
    fun readyFeaturesAreReady() {
        listOf(
            ShellRoute.PLANNER,
            ShellRoute.MEMORY,
            ShellRoute.FINANCE,
            ShellRoute.PC_CONTROL,
            ShellRoute.ANALYTICS
        ).forEach { route ->
            assertEquals(
                FeatureMaturity.READY,
                FeatureMaturityRegistry.of(route),
                "$route should be classified READY"
            )
        }
    }

    @Test
    @DisplayName("partially wired features are classified BETA")
    fun betaFeaturesAreBeta() {
        listOf(
            ShellRoute.AGENT_SWARM,
            ShellRoute.SMART_HOME,
            ShellRoute.VISION_SECURITY
        ).forEach { route ->
            assertEquals(
                FeatureMaturity.BETA,
                FeatureMaturityRegistry.of(route),
                "$route should be classified BETA"
            )
        }
    }

    @Test
    @DisplayName("media jobs pipeline is classified MOCK, not READY")
    fun mediaJobsIsMock() {
        assertEquals(FeatureMaturity.MOCK, FeatureMaturityRegistry.of(ShellRoute.MEDIA_JOBS))
    }

    @Test
    @DisplayName("proactive is classified EXPERIMENTAL, not READY")
    fun proactiveIsExperimental() {
        assertEquals(FeatureMaturity.EXPERIMENTAL, FeatureMaturityRegistry.of(ShellRoute.PROACTIVE))
    }

    @Test
    @DisplayName("sync/pairing requires a phone and is classified UNAVAILABLE")
    fun syncIsUnavailable() {
        assertEquals(FeatureMaturity.UNAVAILABLE, FeatureMaturityRegistry.of(ShellRoute.SYNC))
    }

    @Test
    @DisplayName("unclassified routes fall back to BETA, not an overclaiming READY")
    fun unclassifiedRoutesDefaultToBeta() {
        assertEquals(FeatureMaturity.BETA, FeatureMaturityRegistry.of(ShellRoute.HOME))
    }

    @Test
    @DisplayName("every maturity level has a non-blank label and color for badge rendering")
    fun everyMaturityHasDisplayableLabelAndColor() {
        FeatureMaturity.entries.forEach { maturity ->
            assertFalse(maturity.label.isBlank(), "${maturity.name} must have a non-blank label")
            assertFalse(maturity.color.isBlank(), "${maturity.name} must have a non-blank color")
        }
    }
}
