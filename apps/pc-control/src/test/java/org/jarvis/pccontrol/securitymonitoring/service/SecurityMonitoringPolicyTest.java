package org.jarvis.pccontrol.securitymonitoring.service;

import org.jarvis.common.vision.VisionScreenAnalysisResponse;
import org.jarvis.common.vision.VisionScreenCategory;
import org.jarvis.common.vision.VisionVerificationOutcome;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;
import org.jarvis.pccontrol.securitymonitoring.config.SecurityMonitoringProperties;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringDecision;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringDecisionState;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringRuntimeState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityMonitoringPolicyTest {

    private final SecurityMonitoringProperties properties = new SecurityMonitoringProperties();
    private final SecurityMonitoringPolicy policy;

    SecurityMonitoringPolicyTest() {
        properties.setSimilarityThreshold(0.80d);
        properties.setConsecutiveUnknownDetectionsRequired(3);
        properties.setCooldownBetweenAlerts(Duration.ofMinutes(10));
        properties.getDecision().setAlertScoreThreshold(70);
        properties.getDecision().setHighRiskScoreThreshold(85);
        properties.getDecision().setHighRiskObservationsRequired(2);
        policy = new SecurityMonitoringPolicy(properties, new SecurityRiskScorer(properties));
    }

    @Test
    void triggersAlertAfterConfiguredSuspiciousStreak() {
        VisionVerifyOwnerResponse unknown = new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.UNKNOWN,
                true,
                "test",
                "below threshold",
                0.42d,
                2,
                List.of(),
                Map.of(
                        "identitySignalState", "LOW_CONFIDENCE",
                        "livenessPassed", "true",
                        "livenessAvailable", "true"));
        VisionScreenAnalysisResponse browser = new VisionScreenAnalysisResponse(
                true,
                VisionScreenCategory.BROWSER,
                "browser",
                0.52d,
                false,
                0.30d,
                true,
                Map.of());
        Instant now = Instant.parse("2026-03-27T12:00:00Z");

        MonitoringDecision first = policy.evaluate(unknown, browser, MonitoringRuntimeState.initial(), now);
        MonitoringDecision second = policy.evaluate(unknown, browser, first.nextState(), now.plusSeconds(2));
        MonitoringDecision third = policy.evaluate(unknown, browser, second.nextState(), now.plusSeconds(4));

        assertThat(first.alertTriggered()).isFalse();
        assertThat(second.alertTriggered()).isFalse();
        assertThat(third.alertTriggered()).isTrue();
        assertThat(first.state()).isEqualTo(MonitoringDecisionState.SUSPICIOUS);
        assertThat(third.nextState().consecutiveSuspiciousObservations()).isEqualTo(3);
        assertThat(third.state()).isEqualTo(MonitoringDecisionState.ALERT_TRIGGERED);
        assertThat(third.reason()).isEqualTo("suspicious_observation_threshold_reached");
    }

    @Test
    void highRiskScreenAndFailedLivenessAccelerateAlerting() {
        VisionVerifyOwnerResponse unknown = new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.UNKNOWN,
                true,
                "test",
                "below threshold",
                0.35d,
                1,
                List.of(),
                Map.of(
                        "identitySignalState", "UNKNOWN_CONFIRMED",
                        "livenessPassed", "false",
                        "livenessAvailable", "true"));
        VisionScreenAnalysisResponse sensitiveTerminal = new VisionScreenAnalysisResponse(
                true,
                VisionScreenCategory.TERMINAL,
                "terminal",
                0.80d,
                true,
                0.91d,
                true,
                Map.of());
        Instant now = Instant.parse("2026-03-27T12:00:00Z");

        MonitoringDecision first = policy.evaluate(unknown, sensitiveTerminal, MonitoringRuntimeState.initial(), now);
        MonitoringDecision second = policy.evaluate(unknown, sensitiveTerminal, first.nextState(), now.plusSeconds(2));

        assertThat(first.alertTriggered()).isFalse();
        assertThat(first.severity()).isEqualTo("CRITICAL");
        assertThat(first.state()).isEqualTo(MonitoringDecisionState.HIGH_RISK);
        assertThat(second.alertTriggered()).isTrue();
        assertThat(second.state()).isEqualTo(MonitoringDecisionState.ALERT_TRIGGERED);
        assertThat(second.reason()).isEqualTo("high_risk_temporal_threshold_reached");
    }

    @Test
    void ownerDetectionResetsTemporalCounters() {
        VisionVerifyOwnerResponse owner = new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.OWNER,
                true,
                "test",
                "matched",
                0.91d,
                1,
                List.of(),
                Map.of("identitySignalState", "OWNER_CONFIRMED"));
        VisionScreenAnalysisResponse neutralScreen = new VisionScreenAnalysisResponse(
                true,
                VisionScreenCategory.MEDIA,
                "media",
                0.51d,
                false,
                0.12d,
                false,
                Map.of());

        MonitoringDecision ownerDecision = policy.evaluate(
                owner,
                neutralScreen,
                new MonitoringRuntimeState(2, 2, 1, 65, "UNKNOWN_CONFIRMED", "TERMINAL",
                        Instant.parse("2026-03-27T12:00:00Z"), null),
                Instant.parse("2026-03-27T12:00:04Z"));

        assertThat(ownerDecision.alertTriggered()).isFalse();
        assertThat(ownerDecision.state()).isEqualTo(MonitoringDecisionState.OWNER_CONFIRMED);
        assertThat(ownerDecision.nextState().consecutiveUnknownDetections()).isZero();
        assertThat(ownerDecision.nextState().consecutiveSuspiciousObservations()).isZero();
        assertThat(ownerDecision.nextState().rollingRiskScore()).isZero();
        assertThat(ownerDecision.reason()).isEqualTo("owner_verified");
    }

    @Test
    void respectsCooldownAfterAnAlert() {
        VisionVerifyOwnerResponse unknown = new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.UNKNOWN,
                true,
                "test",
                "below threshold",
                0.33d,
                1,
                List.of(),
                Map.of(
                        "identitySignalState", "UNKNOWN_CONFIRMED",
                        "livenessPassed", "false",
                        "livenessAvailable", "true"));
        VisionScreenAnalysisResponse sensitiveScreen = new VisionScreenAnalysisResponse(
                true,
                VisionScreenCategory.DOCUMENT,
                "doc",
                0.74d,
                true,
                0.82d,
                true,
                Map.of());

        MonitoringRuntimeState stateAfterAlert = new MonitoringRuntimeState(
                3,
                3,
                2,
                90,
                "UNKNOWN_CONFIRMED",
                "DOCUMENT",
                Instant.parse("2026-03-27T12:00:00Z"),
                Instant.parse("2026-03-27T12:00:00Z"));

        MonitoringDecision decision = policy.evaluate(
                unknown,
                sensitiveScreen,
                stateAfterAlert,
                Instant.parse("2026-03-27T12:05:00Z"));

        assertThat(decision.alertTriggered()).isFalse();
        assertThat(decision.cooldownActive()).isTrue();
        assertThat(decision.state()).isEqualTo(MonitoringDecisionState.HIGH_RISK);
        assertThat(decision.reason()).isEqualTo("cooldown_active");
    }

    @Test
    void noFaceObservationDecaysRiskWithoutAlerting() {
        VisionVerifyOwnerResponse noFace = new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.NO_FACE,
                true,
                "test",
                "no face",
                null,
                0,
                List.of(),
                Map.of("identitySignalState", "NO_FACE"));
        VisionScreenAnalysisResponse neutralScreen = new VisionScreenAnalysisResponse(
                true,
                VisionScreenCategory.MEDIA,
                "media",
                0.51d,
                false,
                0.11d,
                false,
                Map.of());

        MonitoringDecision decision = policy.evaluate(
                noFace,
                neutralScreen,
                new MonitoringRuntimeState(2, 2, 1, 60, "UNKNOWN_CONFIRMED", "BROWSER",
                        Instant.parse("2026-03-27T12:00:00Z"), null),
                Instant.parse("2026-03-27T12:00:04Z"));

        assertThat(decision.alertTriggered()).isFalse();
        assertThat(decision.state()).isEqualTo(MonitoringDecisionState.NO_FACE);
        assertThat(decision.nextState().rollingRiskScore()).isLessThan(60);
    }

    @Test
    void unavailableVerificationSkipsWhenConfiguredToSkip() {
        VisionVerifyOwnerResponse unavailable = new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.UNAVAILABLE,
                false,
                "vision-service",
                "unavailable",
                null,
                0,
                List.of(),
                Map.of("identitySignalState", "UNAVAILABLE"));
        VisionScreenAnalysisResponse unavailableScreen = new VisionScreenAnalysisResponse(
                false,
                VisionScreenCategory.UNAVAILABLE,
                "screen unavailable",
                null,
                false,
                null,
                false,
                Map.of());

        MonitoringDecision decision = policy.evaluate(
                unavailable,
                unavailableScreen,
                MonitoringRuntimeState.initial(),
                Instant.parse("2026-03-27T12:00:00Z"));

        assertThat(decision.skipped()).isTrue();
        assertThat(decision.state()).isEqualTo(MonitoringDecisionState.UNAVAILABLE);
        assertThat(decision.alertTriggered()).isFalse();
    }

    @Test
    void unavailableVerificationDecaysRollingRiskWhenSkipIsDisabled() {
        properties.getVision().setSkipOnUnavailable(false);
        VisionVerifyOwnerResponse unavailable = new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.UNAVAILABLE,
                false,
                "vision-service",
                "timeout",
                null,
                0,
                List.of(),
                Map.of("identitySignalState", "UNAVAILABLE"));
        VisionScreenAnalysisResponse unavailableScreen = new VisionScreenAnalysisResponse(
                false,
                VisionScreenCategory.UNAVAILABLE,
                "screen unavailable",
                null,
                false,
                null,
                false,
                Map.of());

        MonitoringDecision decision = policy.evaluate(
                unavailable,
                unavailableScreen,
                new MonitoringRuntimeState(2, 2, 1, 58, "UNKNOWN_CONFIRMED", "DOCUMENT",
                        Instant.parse("2026-03-27T12:00:00Z"), null),
                Instant.parse("2026-03-27T12:00:04Z"));

        assertThat(decision.alertTriggered()).isFalse();
        assertThat(decision.state()).isEqualTo(MonitoringDecisionState.DEGRADED);
        assertThat(decision.nextState().rollingRiskScore()).isLessThan(58);
    }

    @Test
    void degradedLowConfidenceObservationDecaysRiskWithoutBuildingSuspiciousStreak() {
        VisionVerifyOwnerResponse lowConfidence = new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.UNKNOWN,
                true,
                "vision-service",
                "low confidence",
                0.58d,
                1,
                List.of(),
                Map.of("identitySignalState", "LOW_CONFIDENCE"));
        VisionScreenAnalysisResponse unavailableScreen = new VisionScreenAnalysisResponse(
                false,
                VisionScreenCategory.UNAVAILABLE,
                "screen unavailable",
                null,
                false,
                null,
                false,
                Map.of());

        MonitoringDecision decision = policy.evaluate(
                lowConfidence,
                unavailableScreen,
                new MonitoringRuntimeState(1, 1, 0, 52, "LOW_CONFIDENCE", "UNAVAILABLE",
                        Instant.parse("2026-03-27T12:00:00Z"), null),
                Instant.parse("2026-03-27T12:00:04Z"));

        assertThat(decision.alertTriggered()).isFalse();
        assertThat(decision.state()).isEqualTo(MonitoringDecisionState.DEGRADED);
        assertThat(decision.nextState().consecutiveSuspiciousObservations()).isZero();
        assertThat(decision.nextState().rollingRiskScore()).isLessThan(52);
    }
}
