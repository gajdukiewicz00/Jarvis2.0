package org.jarvis.pccontrol.securitymonitoring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jarvis.common.vision.VisionScreenAnalysisResponse;
import org.jarvis.common.vision.VisionScreenCategory;
import org.jarvis.common.vision.VisionVerificationOutcome;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;
import org.jarvis.pccontrol.model.DesktopSystemInfo;
import org.jarvis.pccontrol.securitymonitoring.config.SecurityMonitoringProperties;
import org.jarvis.pccontrol.securitymonitoring.model.CapturedFrame;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringCheckReport;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringDecisionState;
import org.jarvis.pccontrol.securitymonitoring.model.ScreenObservation;
import org.jarvis.pccontrol.securitymonitoring.model.WebcamCaptureResult;
import org.jarvis.pccontrol.securitymonitoring.model.WorkstationMetadata;
import org.jarvis.pccontrol.securitymonitoring.service.impl.DefaultEvidenceCollector;
import org.jarvis.pccontrol.securitymonitoring.service.impl.EmailAlertDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityMonitoringCycleIntegrationTest {

    @Mock
    private JavaMailSender mailSender;

    @TempDir
    Path tempDir;

    @Test
    void keepsQuietAcrossRepeatedOwnerMonitoringCycles() {
        SecurityMonitoringProperties properties = baseProperties(tempDir.resolve("owner"));
        SecurityMonitoringService service = new SecurityMonitoringService(
                properties,
                () -> new WebcamCaptureResult(true, "webcam", "ok", capturedFrame()),
                frame -> ownerResult(),
                frame -> new ScreenObservation(
                        new BufferedImage(80, 80, BufferedImage.TYPE_3BYTE_BGR),
                        new VisionScreenAnalysisResponse(
                                true,
                                VisionScreenCategory.MEDIA,
                                "media",
                                0.62d,
                                false,
                                0.12d,
                                false,
                                Map.of("screenCaptureMode", "robot")),
                        List.of()),
                new SecurityMonitoringPolicy(properties, new SecurityRiskScorer(properties)),
                new DefaultEvidenceCollector(
                        properties,
                        () -> new BufferedImage(80, 80, BufferedImage.TYPE_3BYTE_BGR),
                        () -> workstationMetadata(),
                        new ObjectMapper()),
                new EmailAlertDispatcher(mailSender, properties));

        MonitoringCheckReport first = service.runCheck("manual");
        MonitoringCheckReport second = service.runCheck("manual");
        MonitoringCheckReport third = service.runCheck("manual");

        assertThat(first.decision().state()).isEqualTo(MonitoringDecisionState.OWNER_CONFIRMED);
        assertThat(second.decision().state()).isEqualTo(MonitoringDecisionState.OWNER_CONFIRMED);
        assertThat(third.decision().state()).isEqualTo(MonitoringDecisionState.OWNER_CONFIRMED);
        assertThat(third.decision().rollingRiskScore()).isZero();
        assertThat(third.evidenceBundle()).isNull();
        assertThat(third.incidentContext().trigger()).isEqualTo("manual");
        assertThat(third.incidentContext().identity().outcome()).isEqualTo("OWNER");
        assertThat(third.incidentContext().screen().category()).isEqualTo("MEDIA");
        verify(mailSender, never()).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
    }

    @Test
    void delaysNeutralUnknownAlertUntilTemporalThreshold() throws Exception {
        SecurityMonitoringProperties properties = baseProperties(tempDir.resolve("neutral"));
        properties.getAlert().getEmail().setEnabled(true);
        properties.getAlert().getEmail().setFrom("jarvis@example.com");
        properties.getAlert().getEmail().setTo(List.of("owner@example.com"));

        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        SecurityMonitoringService service = new SecurityMonitoringService(
                properties,
                () -> new WebcamCaptureResult(true, "webcam", "ok", capturedFrame()),
                frame -> neutralUnknownResult(),
                frame -> new ScreenObservation(
                        new BufferedImage(120, 90, BufferedImage.TYPE_3BYTE_BGR),
                        new VisionScreenAnalysisResponse(
                                true,
                                VisionScreenCategory.BROWSER,
                                "browser window",
                                0.64d,
                                false,
                                0.22d,
                                true,
                                Map.of(
                                        "screenCaptureMode", "robot",
                                        "screenCapturedAt", Instant.parse("2026-03-27T12:00:00Z").toString())),
                        List.of()),
                new SecurityMonitoringPolicy(properties, new SecurityRiskScorer(properties)),
                new DefaultEvidenceCollector(
                        properties,
                        () -> new BufferedImage(120, 90, BufferedImage.TYPE_3BYTE_BGR),
                        () -> workstationMetadata(),
                        new ObjectMapper()),
                new EmailAlertDispatcher(mailSender, properties));

        MonitoringCheckReport first = service.runCheck("scheduled");
        MonitoringCheckReport second = service.runCheck("scheduled");
        MonitoringCheckReport third = service.runCheck("scheduled");

        assertThat(first.decision().state()).isEqualTo(MonitoringDecisionState.SUSPICIOUS);
        assertThat(first.decision().alertTriggered()).isFalse();
        assertThat(second.decision().state()).isEqualTo(MonitoringDecisionState.SUSPICIOUS);
        assertThat(second.decision().alertTriggered()).isFalse();
        assertThat(third.decision().state()).isEqualTo(MonitoringDecisionState.ALERT_TRIGGERED);
        assertThat(third.decision().alertTriggered()).isTrue();
        assertThat(third.evidenceBundle()).isNotNull();
        assertThat(third.incidentContext().trigger()).isEqualTo("scheduled");
        assertThat(third.incidentContext().screen().category()).isEqualTo("BROWSER");
        assertThat(third.incidentContext().warnings()).contains("below threshold");

        ArgumentCaptor<MimeMessage> messageCaptor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSubject()).contains("[" + third.decision().severity() + "]");
        assertThat(messageCaptor.getValue().getSubject()).contains("(BROWSER)");
    }

    @Test
    void acceleratesSensitiveScreenAlertAndSendsEvidence() throws Exception {
        SecurityMonitoringProperties properties = baseProperties(tempDir.resolve("alert"));
        properties.getAlert().getEmail().setEnabled(true);
        properties.getAlert().getEmail().setFrom("jarvis@example.com");
        properties.getAlert().getEmail().setTo(List.of("owner@example.com"));

        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        SecurityMonitoringService service = new SecurityMonitoringService(
                properties,
                () -> new WebcamCaptureResult(true, "webcam", "ok", capturedFrame()),
                frame -> sensitiveUnknownResult(),
                frame -> new ScreenObservation(
                        new BufferedImage(120, 90, BufferedImage.TYPE_3BYTE_BGR),
                        new VisionScreenAnalysisResponse(
                                true,
                                VisionScreenCategory.TERMINAL,
                                "terminal appears sensitive",
                                0.82d,
                                true,
                                0.91d,
                                true,
                                Map.of(
                                        "screenCaptureMode", "robot",
                                        "screenCapturedAt", Instant.parse("2026-03-27T12:00:00Z").toString())),
                        List.of()),
                new SecurityMonitoringPolicy(properties, new SecurityRiskScorer(properties)),
                new DefaultEvidenceCollector(
                        properties,
                        () -> new BufferedImage(120, 90, BufferedImage.TYPE_3BYTE_BGR),
                        () -> workstationMetadata(),
                        new ObjectMapper()),
                new EmailAlertDispatcher(mailSender, properties));

        MonitoringCheckReport first = service.runCheck("manual");
        MonitoringCheckReport report = service.runCheck("manual");

        assertThat(first.decision().state()).isEqualTo(MonitoringDecisionState.HIGH_RISK);
        assertThat(first.decision().alertTriggered()).isFalse();
        assertThat(report.decision().alertTriggered()).isTrue();
        assertThat(report.decision().state()).isEqualTo(MonitoringDecisionState.ALERT_TRIGGERED);
        assertThat(report.evidenceBundle()).isNotNull();
        assertThat(Files.exists(report.evidenceBundle().webcamImagePath())).isTrue();
        assertThat(Files.exists(report.evidenceBundle().screenshotPath())).isTrue();
        assertThat(Files.exists(report.evidenceBundle().metadataFilePath())).isTrue();
        assertThat(report.incidentContext().evidence().metadataAttached()).isTrue();
        assertThat(report.incidentContext().screen().category()).isEqualTo("TERMINAL");

        ArgumentCaptor<MimeMessage> messageCaptor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSubject()).contains("[" + report.decision().severity() + "]");
        assertThat(report.incidentContext().warnings()).contains("below threshold");
    }

    private static VisionVerifyOwnerResponse neutralUnknownResult() {
        return new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.UNKNOWN,
                true,
                "vision-service",
                "below threshold",
                0.42d,
                2,
                List.of(),
                Map.of(
                        "identitySignalState", "LOW_CONFIDENCE",
                        "livenessAvailable", "true",
                        "livenessPassed", "true",
                        "livenessConfidence", "0.63"));
    }

    private static VisionVerifyOwnerResponse sensitiveUnknownResult() {
        return new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.UNKNOWN,
                true,
                "vision-service",
                "below threshold",
                0.31d,
                2,
                List.of(),
                Map.of(
                        "identitySignalState", "UNKNOWN_CONFIRMED",
                        "livenessAvailable", "true",
                        "livenessPassed", "false",
                        "livenessConfidence", "0.18"));
    }

    private static SecurityMonitoringProperties baseProperties(Path evidenceDirectory) {
        SecurityMonitoringProperties properties = new SecurityMonitoringProperties();
        properties.setEvidenceDirectory(evidenceDirectory);
        properties.setSamplingInterval(Duration.ofSeconds(2));
        properties.setConsecutiveUnknownDetectionsRequired(3);
        properties.setCooldownBetweenAlerts(Duration.ofMinutes(10));
        properties.getDecision().setSuspiciousScoreThreshold(45);
        properties.getDecision().setAlertScoreThreshold(70);
        properties.getDecision().setHighRiskScoreThreshold(85);
        return properties;
    }

    private static CapturedFrame capturedFrame() {
        return new CapturedFrame(
                new BufferedImage(64, 64, BufferedImage.TYPE_3BYTE_BGR),
                "webcam",
                "camera-0",
                Instant.parse("2026-03-27T12:00:00Z"));
    }

    private static VisionVerifyOwnerResponse ownerResult() {
        return new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.OWNER,
                true,
                "vision-service",
                "matched",
                0.94d,
                2,
                List.of(),
                Map.of(
                        "identitySignalState", "OWNER_CONFIRMED",
                        "livenessAvailable", "true",
                        "livenessPassed", "true",
                        "livenessConfidence", "0.84"));
    }

    private static WorkstationMetadata workstationMetadata() {
        return new WorkstationMetadata(
                new DesktopSystemInfo("linux", "ubuntu", "6.8", "x86_64", "host-1", "gnome", "wayland", List.of("firefox")),
                "Secrets.txt",
                "firefox",
                "kwaqa",
                Map.of("pcControlStubMode", "false"));
    }
}
