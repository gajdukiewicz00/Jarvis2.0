package org.jarvis.visionsecurity.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class VisionSecurityPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsMonitoringStorageEmailAndGpuSettings() {
        contextRunner.withPropertyValues(
                        "vision-security.monitoring.check-interval-ms=1500",
                        "vision-security.monitoring.debounce-unknown-frames=4",
                        "vision-security.monitoring.alert-cooldown-seconds=90",
                        "vision-security.monitoring.owner-grace-frames=3",
                        "vision-security.storage.root=/tmp/jarvis-vision",
                        "vision-security.storage.max-incidents-per-user=12",
                        "vision-security.email.recipient=owner@example.com",
                        "vision-security.email.subject-prefix=[Jarvis Alert]",
                        "vision-security.screen.ocr-language=pol",
                        "vision-security.gpu.prefer-if-available=true",
                        "vision-security.verification.owner-threshold-margin=10.5"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    VisionSecurityProperties properties = context.getBean(VisionSecurityProperties.class);
                    assertThat(properties.getMonitoring().getCheckIntervalMs()).isEqualTo(1500L);
                    assertThat(properties.getMonitoring().getDebounceUnknownFrames()).isEqualTo(4);
                    assertThat(properties.getMonitoring().getAlertCooldownSeconds()).isEqualTo(90L);
                    assertThat(properties.getMonitoring().getOwnerGraceFrames()).isEqualTo(3);
                    assertThat(properties.getStorage().getRoot()).isEqualTo("/tmp/jarvis-vision");
                    assertThat(properties.getStorage().getMaxIncidentsPerUser()).isEqualTo(12);
                    assertThat(properties.getEmail().getRecipient()).isEqualTo("owner@example.com");
                    assertThat(properties.getEmail().getSubjectPrefix()).isEqualTo("[Jarvis Alert]");
                    assertThat(properties.getScreen().getOcrLanguage()).isEqualTo("pol");
                    assertThat(properties.getGpu().isPreferIfAvailable()).isTrue();
                    assertThat(properties.getVerification().getOwnerThresholdMargin()).isEqualTo(10.5);
                });
    }

    @Test
    void bindsV2VerificationSettings() {
        contextRunner.withPropertyValues(
                        "vision-security.verification.normalized-face-size=200",
                        "vision-security.verification.owner-threshold-margin=20.0",
                        "vision-security.verification.uncertain-threshold-margin=40.0",
                        "vision-security.verification.fallback-owner-threshold=80.0",
                        "vision-security.verification.fallback-uncertain-threshold=120.0",
                        "vision-security.verification.min-detection-area-ratio=0.006",
                        "vision-security.verification.face-padding-ratio=0.15",
                        "vision-security.verification.clahe-clip-limit=3.0",
                        "vision-security.verification.clahe-grid-size=16",
                        "vision-security.verification.enable-eye-alignment=false",
                        "vision-security.verification.detection-scale-factor=1.1",
                        "vision-security.verification.detection-min-neighbors=3",
                        "vision-security.verification.alert-on-stranger-with-owner=false",
                        "vision-security.verification.min-frame-brightness=12.5"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    VisionSecurityProperties.Verification v = context.getBean(VisionSecurityProperties.class).getVerification();
                    assertThat(v.getNormalizedFaceSize()).isEqualTo(200);
                    assertThat(v.getOwnerThresholdMargin()).isEqualTo(20.0);
                    assertThat(v.getUncertainThresholdMargin()).isEqualTo(40.0);
                    assertThat(v.getFallbackOwnerThreshold()).isEqualTo(80.0);
                    assertThat(v.getFallbackUncertainThreshold()).isEqualTo(120.0);
                    assertThat(v.getMinDetectionAreaRatio()).isEqualTo(0.006);
                    assertThat(v.getFacePaddingRatio()).isEqualTo(0.15);
                    assertThat(v.getClaheClipLimit()).isEqualTo(3.0);
                    assertThat(v.getClaheGridSize()).isEqualTo(16);
                    assertThat(v.isEnableEyeAlignment()).isFalse();
                    assertThat(v.getDetectionScaleFactor()).isEqualTo(1.1);
                    assertThat(v.getDetectionMinNeighbors()).isEqualTo(3);
                    assertThat(v.isAlertOnStrangerWithOwner()).isFalse();
                    assertThat(v.getMinFrameBrightness()).isEqualTo(12.5);
                });
    }

    @Test
    void bindsEnrollmentQualityGateSettings() {
        contextRunner.withPropertyValues(
                        "vision-security.enrollment.sample-count=8",
                        "vision-security.enrollment.sample-spacing-ms=500",
                        "vision-security.enrollment.capture-timeout-seconds=45",
                        "vision-security.enrollment.min-face-sharpness=40.0",
                        "vision-security.enrollment.min-face-contrast=30.0",
                        "vision-security.enrollment.max-duplicate-hash-distance=6"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    VisionSecurityProperties.Enrollment e = context.getBean(VisionSecurityProperties.class).getEnrollment();
                    assertThat(e.getSampleCount()).isEqualTo(8);
                    assertThat(e.getSampleSpacingMs()).isEqualTo(500L);
                    assertThat(e.getCaptureTimeoutSeconds()).isEqualTo(45L);
                    assertThat(e.getMinFaceSharpness()).isEqualTo(40.0);
                    assertThat(e.getMinFaceContrast()).isEqualTo(30.0);
                    assertThat(e.getMaxDuplicateHashDistance()).isEqualTo(6);
                });
    }

    @Test
    void usesRealisticDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            VisionSecurityProperties props = context.getBean(VisionSecurityProperties.class);
            assertThat(props.getVerification().isEnableEyeAlignment()).isTrue();
            assertThat(props.getVerification().getClaheClipLimit()).isEqualTo(2.5);
            assertThat(props.getVerification().getNormalizedFaceSize()).isEqualTo(160);
            assertThat(props.getEnrollment().getSampleCount()).isGreaterThanOrEqualTo(5);
            assertThat(props.getEnrollment().getMaxDuplicateHashDistance()).isEqualTo(4);
            assertThat(props.getMonitoring().getOwnerGraceFrames()).isEqualTo(2);
            assertThat(props.getVerification().getFallbackOwnerThreshold())
                    .isLessThan(props.getVerification().getFallbackUncertainThreshold());
            assertThat(props.getVerification().isAlertOnStrangerWithOwner())
                    .as("security-first default: stranger next to owner must escalate")
                    .isTrue();
            assertThat(props.getVerification().getMinFrameBrightness()).isPositive();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(VisionSecurityProperties.class)
    static class TestConfig {
    }
}
