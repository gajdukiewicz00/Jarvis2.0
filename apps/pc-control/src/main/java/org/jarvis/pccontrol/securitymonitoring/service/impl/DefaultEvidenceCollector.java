package org.jarvis.pccontrol.securitymonitoring.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;
import org.jarvis.pccontrol.securitymonitoring.config.SecurityMonitoringProperties;
import org.jarvis.pccontrol.securitymonitoring.model.CapturedFrame;
import org.jarvis.pccontrol.securitymonitoring.model.EvidenceBundle;
import org.jarvis.pccontrol.securitymonitoring.model.EvidenceMetadata;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringDecision;
import org.jarvis.pccontrol.securitymonitoring.model.ScreenObservation;
import org.jarvis.pccontrol.securitymonitoring.model.WorkstationIncidentContext;
import org.jarvis.pccontrol.securitymonitoring.model.WorkstationMetadata;
import org.jarvis.pccontrol.securitymonitoring.service.EvidenceCollector;
import org.jarvis.pccontrol.securitymonitoring.service.ScreenshotCaptureService;
import org.jarvis.pccontrol.securitymonitoring.service.WorkstationMetadataProvider;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DefaultEvidenceCollector implements EvidenceCollector {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC);

    private final SecurityMonitoringProperties properties;
    private final ScreenshotCaptureService screenshotCaptureService;
    private final WorkstationMetadataProvider workstationMetadataProvider;
    private final ObjectMapper objectMapper;

    @Override
    public EvidenceBundle collect(String trigger,
                                  CapturedFrame frame,
                                  VisionVerifyOwnerResponse verificationResult,
                                  ScreenObservation screenObservation,
                                  MonitoringDecision decision,
                                  List<String> warnings) throws Exception {
        Path evidenceDirectory = Files.createDirectories(properties.getEvidenceDirectory()
                .resolve(FORMATTER.format(frame.capturedAt())));
        List<String> evidenceWarnings = new ArrayList<>(warnings == null ? List.of() : warnings);

        Path webcamImagePath = writeWebcamImage(evidenceDirectory, frame, evidenceWarnings);

        Path screenshotPath = null;
        String screenshotSource = "none";
        if (properties.getScreenshot().isEnabled()) {
            BufferedImage screenshot = screenObservation != null ? screenObservation.screenshotImage() : null;
            try {
                if (screenshot == null) {
                    screenshot = screenshotCaptureService.captureScreenshot();
                    screenshotSource = "evidence-fallback-capture";
                } else {
                    screenshotSource = "screen-analysis-capture";
                }
                if (screenshot != null) {
                    screenshotPath = evidenceDirectory.resolve("desktop.png");
                    ImageIO.write(screenshot, "png", screenshotPath.toFile());
                }
            } catch (Exception exception) {
                evidenceWarnings.add("Screenshot capture failed: " + exception.getMessage());
                screenshotSource = "unavailable";
            }
        } else {
            screenshotSource = "disabled";
        }

        WorkstationMetadata metadata = collectMetadata(evidenceWarnings);
        Path metadataPath = writeMetadataFile(
                evidenceDirectory.resolve("metadata.json"),
                trigger,
                frame,
                verificationResult,
                screenObservation,
                decision,
                metadata,
                webcamImagePath,
                screenshotPath,
                screenshotSource,
                evidenceWarnings);

        return new EvidenceBundle(
                frame.capturedAt(),
                evidenceDirectory,
                webcamImagePath,
                screenshotPath,
                metadataPath,
                metadata,
                screenObservation == null ? null : screenObservation.analysisResult(),
                decision == null ? 0 : decision.observationRiskScore(),
                decision == null ? "" : decision.severity(),
                evidenceWarnings);
    }

    private Path writeWebcamImage(Path evidenceDirectory,
                                  CapturedFrame frame,
                                  List<String> evidenceWarnings) {
        try {
            Path webcamImagePath = evidenceDirectory.resolve("webcam.jpg");
            ImageIO.write(frame.image(), "jpg", webcamImagePath.toFile());
            return webcamImagePath;
        } catch (Exception exception) {
            evidenceWarnings.add("Webcam evidence write failed: " + exception.getMessage());
            return null;
        }
    }

    private WorkstationMetadata collectMetadata(List<String> evidenceWarnings) {
        try {
            return workstationMetadataProvider.collect();
        } catch (Exception exception) {
            evidenceWarnings.add("Workstation metadata collection failed: " + exception.getMessage());
            return new WorkstationMetadata(null, "", "", "", java.util.Map.of());
        }
    }

    private Path writeMetadataFile(Path metadataPath,
                                   String trigger,
                                   CapturedFrame frame,
                                   VisionVerifyOwnerResponse verificationResult,
                                   ScreenObservation screenObservation,
                                   MonitoringDecision decision,
                                   WorkstationMetadata metadata,
                                   Path webcamImagePath,
                                   Path screenshotPath,
                                   String screenshotSource,
                                   List<String> warnings) {
        try {
            WorkstationIncidentContext incidentContext = WorkstationIncidentContext.of(
                    trigger,
                    frame,
                    verificationResult,
                    screenObservation == null ? null : screenObservation.analysisResult(),
                    decision,
                    metadata,
                    metadataPath.getParent(),
                    webcamImagePath,
                    screenshotPath,
                    metadataPath,
                    warnings);
            EvidenceMetadata payload = new EvidenceMetadata(
                    "jarvis-security-evidence-v1",
                    incidentContext,
                    frame.capturedAt().toString(),
                    frame.provider(),
                    frame.device(),
                    new EvidenceMetadata.Verification(
                            verificationResult.outcome().name(),
                            verificationResult.operational(),
                            verificationResult.provider(),
                            verificationResult.message(),
                            verificationResult.similarity(),
                            verificationResult.referenceImageCount(),
                            verificationResult.detectedFaces(),
                            verificationResult.diagnostics()),
                    new EvidenceMetadata.Screen(
                            screenshotPath != null,
                            screenshotSource,
                            screenObservation != null && screenObservation.analysisResult().operational(),
                            screenObservation == null ? "" : screenObservation.analysisResult().message(),
                            screenObservation == null ? "" : screenObservation.analysisResult().category().name(),
                            screenObservation == null ? null : screenObservation.analysisResult().categoryConfidence(),
                            screenObservation != null && screenObservation.analysisResult().sensitive(),
                            screenObservation == null ? null : screenObservation.analysisResult().sensitiveConfidence(),
                            screenObservation != null && screenObservation.analysisResult().ocrReady(),
                            screenObservation == null ? java.util.Map.of() : screenObservation.analysisResult().diagnostics()),
                    decision == null ? null : new EvidenceMetadata.IncidentDecision(
                            decision.state().name(),
                            decision.skipped(),
                            decision.alertTriggered(),
                            decision.cooldownActive(),
                            decision.reason(),
                            decision.severity(),
                            decision.observationRiskScore(),
                            decision.rollingRiskScore(),
                            decision.evaluatedAt() == null ? "" : decision.evaluatedAt().toString(),
                            decision.nextAlertAllowedAt() == null ? "" : decision.nextAlertAllowedAt().toString(),
                            runtimeStateMetadata(decision.nextState())),
                    new EvidenceMetadata.Workstation(
                            metadata.systemInfo(),
                            metadata.activeWindowTitle(),
                            metadata.activeWindowApplication(),
                            metadata.username(),
                            metadata.runtimeMetadata()),
                    warnings);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(metadataPath.toFile(), payload);
            return metadataPath;
        } catch (Exception exception) {
            warnings.add("Metadata write failed: " + exception.getMessage());
            return null;
        }
    }

    private static EvidenceMetadata.RuntimeState runtimeStateMetadata(org.jarvis.pccontrol.securitymonitoring.model.MonitoringRuntimeState state) {
        if (state == null) {
            return null;
        }
        return new EvidenceMetadata.RuntimeState(
                state.consecutiveUnknownDetections(),
                state.consecutiveSuspiciousObservations(),
                state.consecutiveHighRiskObservations(),
                state.rollingRiskScore(),
                state.lastIdentitySignalState(),
                state.lastScreenCategory(),
                state.lastCheckAt() == null ? "" : state.lastCheckAt().toString(),
                state.lastAlertAt() == null ? "" : state.lastAlertAt().toString());
    }
}
