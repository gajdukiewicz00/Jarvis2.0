package org.jarvis.pccontrol.securitymonitoring.service;

import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jarvis.common.vision.VisionVerificationOutcome;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;
import org.jarvis.pccontrol.securitymonitoring.config.SecurityMonitoringProperties;
import org.jarvis.pccontrol.securitymonitoring.model.AlertPayload;
import org.jarvis.pccontrol.securitymonitoring.model.EvidenceBundle;
import org.jarvis.pccontrol.securitymonitoring.model.WorkstationIncidentContext;
import org.jarvis.pccontrol.securitymonitoring.service.impl.EmailAlertDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailAlertDispatcherTest {

    @Mock
    private JavaMailSender mailSender;

    @TempDir
    Path tempDir;

    @Test
    void attachesWebcamScreenshotAndMetadataFiles() throws Exception {
        SecurityMonitoringProperties properties = new SecurityMonitoringProperties();
        properties.getAlert().getEmail().setEnabled(true);
        properties.getAlert().getEmail().setFrom("jarvis@example.com");
        properties.getAlert().getEmail().setTo(List.of("owner@example.com"));
        EmailAlertDispatcher dispatcher = new EmailAlertDispatcher(mailSender, properties);

        Path webcam = Files.writeString(tempDir.resolve("webcam.jpg"), "webcam");
        Path screenshot = Files.writeString(tempDir.resolve("desktop.png"), "desktop");
        Path metadata = Files.writeString(tempDir.resolve("metadata.json"), "{}");
        EvidenceBundle evidenceBundle = new EvidenceBundle(
                Instant.parse("2026-03-27T12:00:00Z"),
                tempDir,
                webcam,
                screenshot,
                metadata,
                null,
                null,
                88,
                "HIGH",
                List.of());
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        VisionVerifyOwnerResponse verificationResult = new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.UNKNOWN,
                true,
                "vision-service",
                "unknown",
                0.31d,
                1,
                List.of(),
                java.util.Map.of());
        dispatcher.dispatch(new AlertPayload(
                "[Jarvis Security] [HIGH] Workstation risk",
                "body",
                evidenceBundle,
                verificationResult,
                WorkstationIncidentContext.of(
                        "manual",
                        null,
                        verificationResult,
                        null,
                        null,
                        null,
                        evidenceBundle.evidenceDirectory(),
                        evidenceBundle.webcamImagePath(),
                        evidenceBundle.screenshotPath(),
                        evidenceBundle.metadataFilePath(),
                        List.of())));

        ArgumentCaptor<MimeMessage> messageCaptor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        Multipart multipart = (Multipart) messageCaptor.getValue().getContent();
        List<String> attachmentNames = IntStream.range(0, multipart.getCount())
                .mapToObj(index -> {
                    try {
                        return multipart.getBodyPart(index).getFileName();
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                })
                .filter(name -> name != null)
                .toList();

        assertThat(messageCaptor.getValue().getSubject()).contains("[HIGH]");
        assertThat(attachmentNames).containsExactlyInAnyOrder("webcam.jpg", "desktop.png", "metadata.json");
    }

    @Test
    void skipsSendWhenEmailAlertsAreDisabled() throws Exception {
        SecurityMonitoringProperties properties = new SecurityMonitoringProperties();
        properties.getAlert().getEmail().setEnabled(false);
        EmailAlertDispatcher dispatcher = new EmailAlertDispatcher(mailSender, properties);

        VisionVerifyOwnerResponse verificationResult =
                new VisionVerifyOwnerResponse(VisionVerificationOutcome.UNKNOWN, true, "", "", null, 0, List.of(), java.util.Map.of());
        dispatcher.dispatch(new AlertPayload("subject", "body", null, verificationResult,
                WorkstationIncidentContext.of("manual", null, verificationResult, null, null,
                        null, null, null, null, null, List.of())));

        verify(mailSender, never()).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
    }
}
