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
                    assertThat(properties.getStorage().getRoot()).isEqualTo("/tmp/jarvis-vision");
                    assertThat(properties.getStorage().getMaxIncidentsPerUser()).isEqualTo(12);
                    assertThat(properties.getEmail().getRecipient()).isEqualTo("owner@example.com");
                    assertThat(properties.getEmail().getSubjectPrefix()).isEqualTo("[Jarvis Alert]");
                    assertThat(properties.getScreen().getOcrLanguage()).isEqualTo("pol");
                    assertThat(properties.getGpu().isPreferIfAvailable()).isTrue();
                    assertThat(properties.getVerification().getOwnerThresholdMargin()).isEqualTo(10.5);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(VisionSecurityProperties.class)
    static class TestConfig {
    }
}
