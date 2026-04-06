package org.jarvis.pccontrol.securitymonitoring.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityMonitoringPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "pc-control.security-monitoring.enabled=true",
                    "pc-control.security-monitoring.sampling-interval=5s",
                    "pc-control.security-monitoring.similarity-threshold=0.91",
                    "pc-control.security-monitoring.consecutive-unknown-detections-required=4",
                    "pc-control.security-monitoring.cooldown-between-alerts=15m",
                    "pc-control.security-monitoring.screenshot.enabled=false",
                    "pc-control.security-monitoring.screen-analysis.enabled=false",
                    "pc-control.security-monitoring.decision.suspicious-score-threshold=38",
                    "pc-control.security-monitoring.decision.alert-score-threshold=72",
                    "pc-control.security-monitoring.decision.high-risk-score-threshold=88",
                    "pc-control.security-monitoring.decision.high-risk-observations-required=2",
                    "pc-control.security-monitoring.evidence-directory=/tmp/jarvis/evidence",
                    "pc-control.security-monitoring.vision.skip-on-unavailable=false",
                    "pc-control.security-monitoring.alert.email.enabled=true",
                    "pc-control.security-monitoring.alert.email.from=jarvis@example.com",
                    "pc-control.security-monitoring.alert.email.to[0]=owner@example.com",
                    "pc-control.security-monitoring.alert.email.to[1]=");

    @Test
    void bindsNestedSecurityMonitoringProperties() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SecurityMonitoringProperties.class);
            SecurityMonitoringProperties properties = context.getBean(SecurityMonitoringProperties.class);

            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getSamplingInterval()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.getSimilarityThreshold()).isEqualTo(0.91d);
            assertThat(properties.getConsecutiveUnknownDetectionsRequired()).isEqualTo(4);
            assertThat(properties.getCooldownBetweenAlerts()).isEqualTo(Duration.ofMinutes(15));
            assertThat(properties.getEvidenceDirectory()).isEqualTo(Path.of("/tmp/jarvis/evidence"));
            assertThat(properties.getScreenshot().isEnabled()).isFalse();
            assertThat(properties.getScreenAnalysis().isEnabled()).isFalse();
            assertThat(properties.getDecision().getSuspiciousScoreThreshold()).isEqualTo(38);
            assertThat(properties.getDecision().getAlertScoreThreshold()).isEqualTo(72);
            assertThat(properties.getDecision().getHighRiskScoreThreshold()).isEqualTo(88);
            assertThat(properties.getDecision().getHighRiskObservationsRequired()).isEqualTo(2);
            assertThat(properties.getVision().isSkipOnUnavailable()).isFalse();
            assertThat(properties.getAlert().getEmail().isEnabled()).isTrue();
            assertThat(properties.getAlert().getEmail().getFrom()).isEqualTo("jarvis@example.com");
            assertThat(properties.getAlert().getEmail().getTo()).containsExactly("owner@example.com");
        });
    }

    @EnableConfigurationProperties(SecurityMonitoringProperties.class)
    static class TestConfig {
    }
}
