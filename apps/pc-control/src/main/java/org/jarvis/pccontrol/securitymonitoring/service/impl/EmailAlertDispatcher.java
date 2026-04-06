package org.jarvis.pccontrol.securitymonitoring.service.impl;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.securitymonitoring.config.SecurityMonitoringProperties;
import org.jarvis.pccontrol.securitymonitoring.model.AlertPayload;
import org.jarvis.pccontrol.securitymonitoring.service.AlertDispatcher;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailAlertDispatcher implements AlertDispatcher {

    private final JavaMailSender mailSender;
    private final SecurityMonitoringProperties properties;

    @Override
    public void dispatch(AlertPayload payload) throws Exception {
        SecurityMonitoringProperties.Email email = properties.getAlert().getEmail();
        if (!email.isEnabled()) {
            log.info("Security monitoring email alert is disabled; skipping alert dispatch");
            return;
        }
        if (email.getTo().isEmpty()) {
            log.warn("Security monitoring email alert is enabled but no recipients are configured");
            return;
        }

        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
        helper.setTo(email.getTo().toArray(String[]::new));
        if (email.getFrom() != null) {
            helper.setFrom(email.getFrom());
        }
        helper.setSubject(payload.subject());
        helper.setText(payload.message(), false);

        if (payload.evidenceBundle() != null && payload.evidenceBundle().webcamImagePath() != null) {
            helper.addAttachment("webcam.jpg",
                    new FileSystemResource(payload.evidenceBundle().webcamImagePath().toFile()));
        }
        if (payload.evidenceBundle() != null && payload.evidenceBundle().screenshotPath() != null) {
            helper.addAttachment("desktop.png",
                    new FileSystemResource(payload.evidenceBundle().screenshotPath().toFile()));
        }
        if (payload.evidenceBundle() != null && payload.evidenceBundle().metadataFilePath() != null) {
            helper.addAttachment("metadata.json",
                    new FileSystemResource(payload.evidenceBundle().metadataFilePath().toFile()));
        }

        mailSender.send(mimeMessage);
    }
}
