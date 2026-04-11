package org.jarvis.visionsecurity.service;

import org.jarvis.visionsecurity.model.DecisionType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoringDecisionEngineTest {

    @Test
    void requiresConfiguredUnknownFrameCountBeforeAlerting() {
        MonitoringDecisionEngine engine = new MonitoringDecisionEngine(3, Duration.ofSeconds(60));
        Instant now = Instant.parse("2026-04-07T12:00:00Z");

        MonitoringDecisionEngine.Observation first = engine.observe(DecisionType.UNKNOWN_PERSON, now);
        MonitoringDecisionEngine.Observation second = engine.observe(DecisionType.UNKNOWN_PERSON, now.plusSeconds(2));
        MonitoringDecisionEngine.Observation third = engine.observe(DecisionType.UNKNOWN_PERSON, now.plusSeconds(4));

        assertThat(first.alertTriggered()).isFalse();
        assertThat(first.unknownStreak()).isEqualTo(1);
        assertThat(first.effectiveDecision()).isEqualTo(DecisionType.UNCERTAIN);
        assertThat(second.alertTriggered()).isFalse();
        assertThat(second.unknownStreak()).isEqualTo(2);
        assertThat(second.effectiveDecision()).isEqualTo(DecisionType.UNCERTAIN);
        assertThat(third.alertTriggered()).isTrue();
        assertThat(third.unknownStreak()).isZero();
        assertThat(third.lastAlertAt()).isEqualTo(now.plusSeconds(4));
        assertThat(third.effectiveDecision()).isEqualTo(DecisionType.UNKNOWN_PERSON);
    }

    @Test
    void resetsUnknownStreakWhenOwnerOrNoFaceAppears() {
        MonitoringDecisionEngine engine = new MonitoringDecisionEngine(3, Duration.ofSeconds(60));
        Instant now = Instant.parse("2026-04-07T12:00:00Z");

        engine.observe(DecisionType.UNKNOWN_PERSON, now);
        MonitoringDecisionEngine.Observation reset = engine.observe(DecisionType.OWNER_PRESENT, now.plusSeconds(2));
        MonitoringDecisionEngine.Observation next = engine.observe(DecisionType.UNKNOWN_PERSON, now.plusSeconds(4));

        assertThat(reset.alertTriggered()).isFalse();
        assertThat(reset.unknownStreak()).isZero();
        assertThat(next.unknownStreak()).isEqualTo(1);
    }

    @Test
    void enforcesCooldownBeforeRaisingAnotherUnknownAlert() {
        MonitoringDecisionEngine engine = new MonitoringDecisionEngine(2, Duration.ofSeconds(10));
        Instant now = Instant.parse("2026-04-07T12:00:00Z");

        MonitoringDecisionEngine.Observation firstAlert = engine.observe(DecisionType.UNKNOWN_PERSON, now);
        firstAlert = engine.observe(DecisionType.UNKNOWN_PERSON, now.plusSeconds(2));
        MonitoringDecisionEngine.Observation blocked = engine.observe(DecisionType.UNKNOWN_PERSON, now.plusSeconds(4));
        blocked = engine.observe(DecisionType.UNKNOWN_PERSON, now.plusSeconds(6));
        MonitoringDecisionEngine.Observation secondAlert = engine.observe(DecisionType.UNKNOWN_PERSON, now.plusSeconds(13));

        assertThat(firstAlert.alertTriggered()).isTrue();
        assertThat(blocked.alertTriggered()).isFalse();
        assertThat(blocked.unknownStreak()).isEqualTo(2);
        assertThat(secondAlert.alertTriggered()).isTrue();
        assertThat(secondAlert.lastAlertAt()).isEqualTo(now.plusSeconds(13));
    }

    @Test
    void keepsOwnerStateAcrossSingleNeutralOrUnknownFramesWithinGraceWindow() {
        MonitoringDecisionEngine engine = new MonitoringDecisionEngine(3, Duration.ofSeconds(60), 2);
        Instant now = Instant.parse("2026-04-07T12:00:00Z");

        MonitoringDecisionEngine.Observation owner = engine.observe(DecisionType.OWNER_PRESENT, now);
        MonitoringDecisionEngine.Observation noFace = engine.observe(DecisionType.NO_FACE, now.plusSeconds(1));
        MonitoringDecisionEngine.Observation uncertain = engine.observe(DecisionType.UNCERTAIN, now.plusSeconds(2));
        MonitoringDecisionEngine.Observation unknown = engine.observe(DecisionType.UNKNOWN_PERSON, now.plusSeconds(3));

        assertThat(owner.effectiveDecision()).isEqualTo(DecisionType.OWNER_PRESENT);
        assertThat(noFace.effectiveDecision()).isEqualTo(DecisionType.OWNER_PRESENT);
        assertThat(uncertain.effectiveDecision()).isEqualTo(DecisionType.OWNER_PRESENT);
        assertThat(unknown.effectiveDecision()).isEqualTo(DecisionType.OWNER_PRESENT);
        assertThat(unknown.unknownStreak()).isEqualTo(1);
        assertThat(unknown.alertTriggered()).isFalse();
    }
}
