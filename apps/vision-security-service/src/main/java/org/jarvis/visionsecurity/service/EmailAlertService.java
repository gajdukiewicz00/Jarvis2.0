package org.jarvis.visionsecurity.service;

import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CapabilityStatus;
import org.jarvis.visionsecurity.model.EmailDelivery;
import org.jarvis.visionsecurity.model.IncidentRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Sends incident/test alert emails when both a recipient
 * ({@code VISION_SECURITY_EMAIL_RECIPIENT}) and SMTP delivery
 * ({@code SPRING_MAIL_HOST} plus optional user/password) are configured.
 *
 * <p>{@code application.yml} always defines {@code spring.mail.host} (falling
 * back to an empty string when {@code SPRING_MAIL_HOST} is unset). That keeps
 * the property present in the environment, which is enough for Spring Boot's
 * {@code MailSenderAutoConfiguration} to register a {@link JavaMailSender}
 * bean even though no real SMTP server was ever configured. So an empty
 * {@link #smtpHost} is checked explicitly here — relying on
 * {@code mailSenderProvider.getIfAvailable() == null} alone is not sufficient
 * to detect "SMTP not configured".
 */
@Service
public class EmailAlertService {

    private final VisionSecurityProperties properties;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String smtpHost;

    public EmailAlertService(
            VisionSecurityProperties properties,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${spring.mail.host:}") String smtpHost) {
        this.properties = properties;
        this.mailSenderProvider = mailSenderProvider;
        this.smtpHost = smtpHost == null ? "" : smtpHost;
    }

    public CapabilityStatus capabilityStatus() {
        if (properties.getEmail().getRecipient().isBlank()) {
            return new CapabilityStatus("UNAVAILABLE", "Set VISION_SECURITY_EMAIL_RECIPIENT to enable alerts");
        }
        if (!isSmtpConfigured() || mailSenderProvider.getIfAvailable() == null) {
            return new CapabilityStatus("UNAVAILABLE",
                    "Set SPRING_MAIL_HOST (and SPRING_MAIL_USERNAME/SPRING_MAIL_PASSWORD if required) to enable SMTP delivery");
        }
        return new CapabilityStatus("AVAILABLE", "Configured for " + properties.getEmail().getRecipient());
    }

    public EmailDelivery sendIncidentAlert(IncidentRecord incident) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (properties.getEmail().getRecipient().isBlank()) {
            return new EmailDelivery(false, false, "Alert email recipient is not configured");
        }
        if (!isSmtpConfigured() || sender == null) {
            return new EmailDelivery(false, false, "SMTP host is not configured (set SPRING_MAIL_HOST)");
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
        if (!isSmtpConfigured() || sender == null) {
            return new EmailDelivery(false, false, "SMTP host is not configured (set SPRING_MAIL_HOST)");
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

    private boolean isSmtpConfigured() {
        return !smtpHost.isBlank();
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
