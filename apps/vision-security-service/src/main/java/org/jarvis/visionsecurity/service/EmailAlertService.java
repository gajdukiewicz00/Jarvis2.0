package org.jarvis.visionsecurity.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CapabilityStatus;
import org.jarvis.visionsecurity.model.EmailDelivery;
import org.jarvis.visionsecurity.model.IncidentRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
@RequiredArgsConstructor
public class EmailAlertService {

    private final VisionSecurityProperties properties;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    public CapabilityStatus capabilityStatus() {
        if (properties.getEmail().getRecipient().isBlank()) {
            return new CapabilityStatus("UNAVAILABLE", "Set VISION_SECURITY_EMAIL_RECIPIENT to enable alerts");
        }
        if (mailSenderProvider.getIfAvailable() == null) {
            return new CapabilityStatus("UNAVAILABLE", "Set spring.mail.* settings to enable SMTP delivery");
        }
        return new CapabilityStatus("AVAILABLE", "Configured for " + properties.getEmail().getRecipient());
    }

    public EmailDelivery sendIncidentAlert(IncidentRecord incident) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (properties.getEmail().getRecipient().isBlank()) {
            return new EmailDelivery(false, false, "Alert email recipient is not configured");
        }
        if (sender == null) {
            return new EmailDelivery(false, false, "JavaMailSender is not configured");
        }

        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(properties.getEmail().getRecipient());
            if (!properties.getEmail().getFrom().isBlank()) {
                helper.setFrom(properties.getEmail().getFrom());
            }
            helper.setSubject(properties.getEmail().getSubjectPrefix() + " Unknown person detected");
            helper.setText(buildBody(incident), false);
            attachIfPresent(helper, incident.webcamPhotoPath(), "webcam-photo.png");
            attachIfPresent(helper, incident.screenshotPath(), "screenshot.png");
            attachIfPresent(helper, incident.ocrTextPath(), "screen-ocr.txt");
            sender.send(message);
            return new EmailDelivery(true, true, "Alert email sent");
        } catch (Exception ex) {
            return new EmailDelivery(true, false, ex.getMessage() == null ? "Email delivery failed" : ex.getMessage());
        }
    }

    public EmailDelivery sendTestAlert(String userId) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (properties.getEmail().getRecipient().isBlank()) {
            return new EmailDelivery(false, false, "Alert email recipient is not configured");
        }
        if (sender == null) {
            return new EmailDelivery(false, false, "JavaMailSender is not configured");
        }

        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setTo(properties.getEmail().getRecipient());
            if (!properties.getEmail().getFrom().isBlank()) {
                helper.setFrom(properties.getEmail().getFrom());
            }
            helper.setSubject(properties.getEmail().getSubjectPrefix() + " Test alert");
            helper.setText("Jarvis Vision Security test alert for user " + userId, false);
            sender.send(message);
            return new EmailDelivery(true, true, "Test alert sent");
        } catch (Exception ex) {
            return new EmailDelivery(true, false, ex.getMessage() == null ? "Test alert failed" : ex.getMessage());
        }
    }

    private String buildBody(IncidentRecord incident) {
        return """
                Jarvis Vision Security detected a confirmed unknown person.

                Incident ID: %s
                User: %s
                Timestamp: %s
                Decision: %s
                Reason: %s
                Active window: %s
                Active process: %s
                Tags: %s

                OCR excerpt:
                %s

                Local evidence directory:
                %s
                """.formatted(
                incident.incidentId(),
                incident.userId(),
                incident.createdAt(),
                incident.decision(),
                incident.reason(),
                incident.screenContext().activeWindowTitle(),
                incident.screenContext().activeProcessName(),
                String.join(", ", incident.semanticTags()),
                incident.screenContext().ocrText(),
                incident.incidentDirectory()
        );
    }

    private void attachIfPresent(MimeMessageHelper helper, String filePath, String attachmentName) throws Exception {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        Path path = Path.of(filePath);
        if (Files.isRegularFile(path)) {
            helper.addAttachment(attachmentName, new FileSystemResource(path.toFile()));
        }
    }
}
