package org.jarvis.pccontrol.securitymonitoring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "pc-control.security-monitoring")
public class SecurityMonitoringProperties {

    private boolean enabled;
    private Duration samplingInterval = Duration.ofSeconds(2);
    private double similarityThreshold = 0.82d;
    private int consecutiveUnknownDetectionsRequired = 3;
    private Duration cooldownBetweenAlerts = Duration.ofMinutes(10);
    private Path evidenceDirectory = Path.of(System.getProperty("user.home"),
            ".jarvis", "security-monitoring", "evidence");
    private final Webcam webcam = new Webcam();
    private final Screenshot screenshot = new Screenshot();
    private final Vision vision = new Vision();
    private final ScreenAnalysis screenAnalysis = new ScreenAnalysis();
    private final Decision decision = new Decision();
    private final Alert alert = new Alert();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getSamplingInterval() {
        return samplingInterval;
    }

    public void setSamplingInterval(Duration samplingInterval) {
        this.samplingInterval = samplingInterval == null ? Duration.ofSeconds(2) : samplingInterval;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    public int getConsecutiveUnknownDetectionsRequired() {
        return consecutiveUnknownDetectionsRequired;
    }

    public void setConsecutiveUnknownDetectionsRequired(int consecutiveUnknownDetectionsRequired) {
        this.consecutiveUnknownDetectionsRequired = Math.max(1, consecutiveUnknownDetectionsRequired);
    }

    public Duration getCooldownBetweenAlerts() {
        return cooldownBetweenAlerts;
    }

    public void setCooldownBetweenAlerts(Duration cooldownBetweenAlerts) {
        this.cooldownBetweenAlerts = cooldownBetweenAlerts == null ? Duration.ofMinutes(10) : cooldownBetweenAlerts;
    }

    public Path getEvidenceDirectory() {
        return evidenceDirectory;
    }

    public void setEvidenceDirectory(Path evidenceDirectory) {
        this.evidenceDirectory = evidenceDirectory == null
                ? Path.of(System.getProperty("user.home"), ".jarvis", "security-monitoring", "evidence")
                : evidenceDirectory;
    }

    public Webcam getWebcam() {
        return webcam;
    }

    public Screenshot getScreenshot() {
        return screenshot;
    }

    public Vision getVision() {
        return vision;
    }

    public ScreenAnalysis getScreenAnalysis() {
        return screenAnalysis;
    }

    public Decision getDecision() {
        return decision;
    }

    public Alert getAlert() {
        return alert;
    }

    public static class Webcam {

        private int deviceIndex;
        private int captureWidth = 640;
        private int captureHeight = 480;
        private int warmupFrames = 2;

        public int getDeviceIndex() {
            return deviceIndex;
        }

        public void setDeviceIndex(int deviceIndex) {
            this.deviceIndex = Math.max(0, deviceIndex);
        }

        public int getCaptureWidth() {
            return captureWidth;
        }

        public void setCaptureWidth(int captureWidth) {
            this.captureWidth = Math.max(160, captureWidth);
        }

        public int getCaptureHeight() {
            return captureHeight;
        }

        public void setCaptureHeight(int captureHeight) {
            this.captureHeight = Math.max(120, captureHeight);
        }

        public int getWarmupFrames() {
            return warmupFrames;
        }

        public void setWarmupFrames(int warmupFrames) {
            this.warmupFrames = Math.max(1, warmupFrames);
        }
    }

    public static class Screenshot {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Vision {

        private boolean skipOnUnavailable = true;

        public boolean isSkipOnUnavailable() {
            return skipOnUnavailable;
        }

        public void setSkipOnUnavailable(boolean skipOnUnavailable) {
            this.skipOnUnavailable = skipOnUnavailable;
        }
    }

    public static class ScreenAnalysis {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Decision {

        private int suspiciousScoreThreshold = 45;
        private int alertScoreThreshold = 70;
        private int highRiskScoreThreshold = 85;
        private int highRiskObservationsRequired = 2;

        public int getSuspiciousScoreThreshold() {
            return suspiciousScoreThreshold;
        }

        public void setSuspiciousScoreThreshold(int suspiciousScoreThreshold) {
            this.suspiciousScoreThreshold = clampScore(suspiciousScoreThreshold);
        }

        public int getAlertScoreThreshold() {
            return alertScoreThreshold;
        }

        public void setAlertScoreThreshold(int alertScoreThreshold) {
            this.alertScoreThreshold = clampScore(alertScoreThreshold);
        }

        public int getHighRiskScoreThreshold() {
            return highRiskScoreThreshold;
        }

        public void setHighRiskScoreThreshold(int highRiskScoreThreshold) {
            this.highRiskScoreThreshold = clampScore(highRiskScoreThreshold);
        }

        public int getHighRiskObservationsRequired() {
            return highRiskObservationsRequired;
        }

        public void setHighRiskObservationsRequired(int highRiskObservationsRequired) {
            this.highRiskObservationsRequired = Math.max(1, highRiskObservationsRequired);
        }

        private static int clampScore(int value) {
            return Math.max(1, Math.min(100, value));
        }
    }

    public static class Alert {

        private final Email email = new Email();

        public Email getEmail() {
            return email;
        }
    }

    public static class Email {

        private boolean enabled;
        private String from;
        private List<String> to = new ArrayList<>();
        private String subjectPrefix = "[Jarvis Security]";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from == null || from.isBlank() ? null : from.trim();
        }

        public List<String> getTo() {
            return List.copyOf(to);
        }

        public void setTo(List<String> to) {
            this.to = to == null ? new ArrayList<>() : to.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }

        public String getSubjectPrefix() {
            return subjectPrefix;
        }

        public void setSubjectPrefix(String subjectPrefix) {
            this.subjectPrefix = subjectPrefix == null || subjectPrefix.isBlank()
                    ? "[Jarvis Security]" : subjectPrefix.trim();
        }
    }
}
