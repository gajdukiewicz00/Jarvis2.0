package org.jarvis.visionsecurity.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CapabilityStatus;
import org.jarvis.visionsecurity.model.DecisionType;
import org.jarvis.visionsecurity.model.EmailDelivery;
import org.jarvis.visionsecurity.model.IncidentRecord;
import org.jarvis.visionsecurity.model.ScreenContextEvidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers {@link EmailAlertService}, mocking the JavaMailSender/ObjectProvider layer. */
class EmailAlertServiceTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<JavaMailSender> mailSenderProvider = mock(ObjectProvider.class);
    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private VisionSecurityProperties properties;
    private EmailAlertService service;

    @BeforeEach
    void setUp() {
        properties = new VisionSecurityProperties();
        properties.getEmail().setRecipient("owner@example.com");
        properties.getEmail().setFrom("jarvis@example.com");
        properties.getEmail().setSubjectPrefix("[Jarvis]");
        service = new EmailAlertService(properties, mailSenderProvider);
    }

    private MimeMessage realMimeMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    @Test
    void capabilityIsUnavailableWhenRecipientIsBlank() {
        properties.getEmail().setRecipient("");

        CapabilityStatus status = service.capabilityStatus();

        assertThat(status.state()).isEqualTo("UNAVAILABLE");
        assertThat(status.detail()).contains("VISION_SECURITY_EMAIL_RECIPIENT");
    }

    @Test
    void capabilityIsUnavailableWhenNoMailSenderConfigured() {
        when(mailSenderProvider.getIfAvailable()).thenReturn(null);

        CapabilityStatus status = service.capabilityStatus();

        assertThat(status.state()).isEqualTo("UNAVAILABLE");
        assertThat(status.detail()).contains("spring.mail");
    }

    @Test
    void capabilityIsAvailableWhenRecipientAndSenderConfigured() {
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);

        CapabilityStatus status = service.capabilityStatus();

        assertThat(status.state()).isEqualTo("AVAILABLE");
        assertThat(status.detail()).contains("owner@example.com");
    }

    @Test
    void sendIncidentAlertReturnsNotConfiguredWhenRecipientBlank() {
        properties.getEmail().setRecipient("");

        EmailDelivery delivery = service.sendIncidentAlert(incident(null, null, null));

        assertThat(delivery.attempted()).isFalse();
        assertThat(delivery.sent()).isFalse();
        assertThat(delivery.message()).contains("recipient is not configured");
    }

    @Test
    void sendIncidentAlertReturnsNotConfiguredWhenSenderMissing() {
        when(mailSenderProvider.getIfAvailable()).thenReturn(null);

        EmailDelivery delivery = service.sendIncidentAlert(incident(null, null, null));

        assertThat(delivery.attempted()).isFalse();
        assertThat(delivery.message()).contains("JavaMailSender is not configured");
    }

    @Test
    void sendIncidentAlertSendsWithAttachmentsWhenEvidenceFilesExist(@TempDir Path tempDir) throws Exception {
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        when(mailSender.createMimeMessage()).thenReturn(realMimeMessage());
        Path webcam = tempDir.resolve("webcam.png");
        Path screenshot = tempDir.resolve("screenshot.png");
        Path ocr = tempDir.resolve("screen-ocr.txt");
        Files.writeString(webcam, "fake-image-bytes");
        Files.writeString(screenshot, "fake-image-bytes");
        Files.writeString(ocr, "some text");

        EmailDelivery delivery = service.sendIncidentAlert(incident(webcam.toString(), screenshot.toString(), ocr.toString()));

        assertThat(delivery.attempted()).isTrue();
        assertThat(delivery.sent()).isTrue();
        assertThat(delivery.message()).isEqualTo("Alert email sent");
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendIncidentAlertSkipsMissingOrBlankAttachmentPaths() throws Exception {
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        when(mailSender.createMimeMessage()).thenReturn(realMimeMessage());

        EmailDelivery delivery = service.sendIncidentAlert(incident(null, "", "/no/such/file.txt"));

        assertThat(delivery.sent()).isTrue();
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendIncidentAlertReturnsFailureWhenSendThrows() {
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        when(mailSender.createMimeMessage()).thenReturn(realMimeMessage());
        doThrow(new org.springframework.mail.MailSendException("smtp down"))
                .when(mailSender).send(any(MimeMessage.class));

        EmailDelivery delivery = service.sendIncidentAlert(incident(null, null, null));

        assertThat(delivery.attempted()).isTrue();
        assertThat(delivery.sent()).isFalse();
        assertThat(delivery.message()).contains("smtp down");
    }

    @Test
    void sendTestAlertReturnsNotConfiguredWhenRecipientBlank() {
        properties.getEmail().setRecipient("");

        EmailDelivery delivery = service.sendTestAlert("owner");

        assertThat(delivery.attempted()).isFalse();
        assertThat(delivery.message()).contains("recipient is not configured");
    }

    @Test
    void sendTestAlertReturnsNotConfiguredWhenSenderMissing() {
        when(mailSenderProvider.getIfAvailable()).thenReturn(null);

        EmailDelivery delivery = service.sendTestAlert("owner");

        assertThat(delivery.attempted()).isFalse();
        assertThat(delivery.message()).contains("JavaMailSender is not configured");
    }

    @Test
    void sendTestAlertSendsSuccessfully() {
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        when(mailSender.createMimeMessage()).thenReturn(realMimeMessage());

        EmailDelivery delivery = service.sendTestAlert("owner");

        assertThat(delivery.attempted()).isTrue();
        assertThat(delivery.sent()).isTrue();
        assertThat(delivery.message()).isEqualTo("Test alert sent");
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendTestAlertReturnsFailureWhenSendThrows() {
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        when(mailSender.createMimeMessage()).thenReturn(realMimeMessage());
        doThrow(new org.springframework.mail.MailSendException("smtp down"))
                .when(mailSender).send(any(MimeMessage.class));

        EmailDelivery delivery = service.sendTestAlert("owner");

        assertThat(delivery.attempted()).isTrue();
        assertThat(delivery.sent()).isFalse();
        assertThat(delivery.message()).contains("smtp down");
    }

    @Test
    void sendIncidentAlertOmitsFromHeaderWhenFromIsBlank() {
        properties.getEmail().setFrom("");
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        when(mailSender.createMimeMessage()).thenReturn(realMimeMessage());

        EmailDelivery delivery = service.sendIncidentAlert(incident(null, null, null));

        assertThat(delivery.sent()).isTrue();
    }

    private IncidentRecord incident(String webcamPath, String screenshotPath, String ocrTextPath) {
        return new IncidentRecord(
                "20260501T000000Z-abc",
                "owner",
                Instant.parse("2026-05-01T00:00:00Z"),
                DecisionType.UNKNOWN_PERSON,
                1,
                "Detected faces stayed outside the owner threshold",
                List.of("GENERAL_DESKTOP"),
                new ScreenContextEvidence("Terminal", "bash", "some ocr text", List.of("DEVELOPMENT")),
                null,
                "/tmp/incident",
                webcamPath,
                screenshotPath,
                ocrTextPath,
                new EmailDelivery(false, false, "Email not attempted yet")
        );
    }
}
