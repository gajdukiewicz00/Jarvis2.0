package org.jarvis.pccontrol.securitymonitoring.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.vision.VisionScreenAnalysisResponse;
import org.jarvis.common.vision.VisionScreenCategory;
import org.jarvis.common.vision.VisionVerificationOutcome;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;
import org.jarvis.pccontrol.securitymonitoring.config.SecurityMonitoringProperties;
import org.jarvis.pccontrol.securitymonitoring.model.AlertPayload;
import org.jarvis.pccontrol.securitymonitoring.model.EvidenceBundle;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringCheckReport;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringDecision;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringDecisionState;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringRuntimeState;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringStatusSnapshot;
import org.jarvis.pccontrol.securitymonitoring.model.ScreenObservation;
import org.jarvis.pccontrol.securitymonitoring.model.WebcamCaptureResult;
import org.jarvis.pccontrol.securitymonitoring.model.WorkstationIncidentContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityMonitoringService {

    private final SecurityMonitoringProperties properties;
    private final WebcamFrameSource webcamFrameSource;
    private final VisionVerificationService visionVerificationService;
    private final ScreenAnalysisService screenAnalysisService;
    private final SecurityMonitoringPolicy securityMonitoringPolicy;
    private final EvidenceCollector evidenceCollector;
    private final AlertDispatcher alertDispatcher;

    private final AtomicReference<MonitoringRuntimeState> runtimeState =
            new AtomicReference<>(MonitoringRuntimeState.initial());
    private final AtomicReference<MonitoringCheckReport> lastReport = new AtomicReference<>();

    public synchronized MonitoringCheckReport runCheck(String trigger) {
        List<String> warnings = new ArrayList<>();
        WebcamCaptureResult captureResult = webcamFrameSource.captureFrame();
        if (!captureResult.operational() || captureResult.frame() == null) {
            warnings.add(captureResult.message());
            MonitoringRuntimeState nextState = new MonitoringRuntimeState(
                    0,
                    0,
                    0,
                    Math.max(0, runtimeState.get().rollingRiskScore() - 10),
                    "UNAVAILABLE",
                    "",
                    Instant.now(),
                    runtimeState.get().lastAlertAt());
            MonitoringDecision decision = new MonitoringDecision(
                    MonitoringDecisionState.UNAVAILABLE,
                    true,
                    false,
                    false,
                    "webcam_unavailable",
                    "LOW",
                    10,
                    nextState.rollingRiskScore(),
                    Instant.now(),
                    nextAlertAllowedAt(),
                    nextState);
            VisionVerifyOwnerResponse verificationResult = new VisionVerifyOwnerResponse(
                    VisionVerificationOutcome.UNAVAILABLE,
                    false,
                    captureResult.provider(),
                    captureResult.message(),
                    null,
                    0,
                    List.of(),
                    java.util.Map.of());
            VisionScreenAnalysisResponse screenAnalysisResult =
                    unavailableScreenAnalysis("Webcam capture failed before screen analysis");
            WorkstationIncidentContext incidentContext = buildIncidentContext(
                    trigger,
                    null,
                    verificationResult,
                    screenAnalysisResult,
                    decision,
                    null,
                    null,
                    warnings);
            MonitoringCheckReport report = new MonitoringCheckReport(
                    trigger,
                    verificationResult,
                    screenAnalysisResult,
                    decision,
                    null,
                    incidentContext,
                    warnings);
            runtimeState.set(nextState);
            lastReport.set(report);
            return report;
        }

        VisionVerifyOwnerResponse verificationResult = visionVerificationService.verifyOwner(captureResult.frame());
        if (!verificationResult.message().isBlank()) {
            warnings.add(verificationResult.message());
        }

        ScreenObservation screenObservation = screenAnalysisService.observe(captureResult.frame());
        warnings.addAll(screenObservation.warnings());

        MonitoringDecision decision = securityMonitoringPolicy.evaluate(
                verificationResult,
                screenObservation.analysisResult(),
                runtimeState.get(),
                captureResult.frame().capturedAt());

        EvidenceBundle evidenceBundle = null;
        if (decision.alertTriggered()) {
            try {
                evidenceBundle = evidenceCollector.collect(
                        trigger,
                        captureResult.frame(),
                        verificationResult,
                        screenObservation,
                        decision,
                        warnings);
                warnings.clear();
                warnings.addAll(evidenceBundle.warnings());
                alertDispatcher.dispatch(buildAlertPayload(trigger, evidenceBundle, verificationResult, decision));
            } catch (Exception exception) {
                warnings.add("Alert dispatch pipeline failed: " + exception.getMessage());
                log.error("Security monitoring alert pipeline failed", exception);
            }
        }

        WorkstationIncidentContext incidentContext = buildIncidentContext(
                trigger,
                captureResult.frame(),
                verificationResult,
                screenObservation.analysisResult(),
                decision,
                evidenceBundle == null ? null : evidenceBundle.workstationMetadata(),
                evidenceBundle,
                warnings);

        runtimeState.set(decision.nextState());
        MonitoringCheckReport report = new MonitoringCheckReport(
                trigger,
                verificationResult,
                screenObservation.analysisResult(),
                decision,
                evidenceBundle,
                incidentContext,
                warnings);
        lastReport.set(report);
        return report;
    }

    public MonitoringStatusSnapshot status() {
        return new MonitoringStatusSnapshot(
                properties.isEnabled(),
                properties.getSamplingInterval(),
                runtimeState.get(),
                lastReport.get());
    }

    private AlertPayload buildAlertPayload(String trigger,
                                           EvidenceBundle evidenceBundle,
                                           VisionVerifyOwnerResponse verificationResult,
                                           MonitoringDecision decision) {
        WorkstationIncidentContext context = buildIncidentContext(
                trigger,
                null,
                verificationResult,
                evidenceBundle == null ? null : evidenceBundle.screenAnalysisResult(),
                decision,
                evidenceBundle == null ? null : evidenceBundle.workstationMetadata(),
                evidenceBundle,
                evidenceBundle == null ? List.of() : evidenceBundle.warnings());
        String hostname = context.workstation().hostname().isBlank() ? "unknown-host" : context.workstation().hostname();
        String severity = context.decision().severity().isBlank() ? "MEDIUM" : context.decision().severity();
        String screenCategory = context.screen().category().isBlank()
                ? VisionScreenCategory.UNAVAILABLE.name()
                : context.screen().category();
        String subject = properties.getAlert().getEmail().getSubjectPrefix()
                + " [" + severity + "] Suspicious workstation activity on " + hostname + " (" + screenCategory + ")";

        StringBuilder body = new StringBuilder();
        body.append("Jarvis workstation security incident summary").append('\n');
        body.append("Trigger: ").append(context.trigger()).append('\n');
        body.append("Host: ").append(hostname).append('\n');
        body.append("Time: ").append(context.capturedAt()).append('\n');
        body.append("Decision state: ").append(context.decision().state()).append('\n');
        body.append("Reason: ").append(context.decision().reason()).append('\n');
        body.append("Severity: ").append(context.decision().severity()).append('\n');
        body.append("Observation risk score: ").append(context.decision().observationRiskScore()).append('\n');
        body.append("Rolling risk score: ").append(context.decision().rollingRiskScore()).append('\n');
        if (!context.decision().nextAlertAllowedAt().isBlank()) {
            body.append("Next alert allowed at: ").append(context.decision().nextAlertAllowedAt()).append('\n');
        }
        body.append('\n');

        body.append("Identity").append('\n');
        body.append("Verification outcome: ").append(context.identity().outcome()).append('\n');
        body.append("Verification operational: ").append(context.identity().operational()).append('\n');
        body.append("Verification provider: ").append(context.identity().provider()).append('\n');
        body.append("Similarity: ").append(context.identity().similarity()).append('\n');
        if (!context.identity().message().isBlank()) {
            body.append("Verification message: ").append(context.identity().message()).append('\n');
        }
        appendContextLine(body, context.identity().identitySignalState(), "Identity signal");
        appendContextLine(body, context.identity().identitySignalConfidence(), "Identity confidence");
        appendContextLine(body, context.identity().livenessPassed(), "Liveness passed");
        appendContextLine(body, context.identity().livenessConfidence(), "Liveness confidence");
        body.append('\n');

        body.append("Screen").append('\n');
        body.append("Screen analysis operational: ").append(context.screen().operational()).append('\n');
        body.append("Category: ").append(context.screen().category()).append('\n');
        body.append("Category confidence: ").append(context.screen().categoryConfidence()).append('\n');
        body.append("Sensitive: ").append(context.screen().sensitive()).append('\n');
        body.append("Sensitive confidence: ").append(context.screen().sensitiveConfidence()).append('\n');
        body.append("OCR ready: ").append(context.screen().ocrReady()).append('\n');
        if (!context.screen().message().isBlank()) {
            body.append("Screen analysis message: ").append(context.screen().message()).append('\n');
        }
        appendContextLine(body, context.screen().captureMode(), "Screen capture mode");
        appendContextLine(body, context.screen().capturedAt(), "Screen captured at");
        body.append('\n');

        body.append("Workstation").append('\n');
        body.append("Username: ").append(context.workstation().username()).append('\n');
        body.append("Active window: ").append(context.workstation().activeWindowTitle()).append('\n');
        body.append("Application: ").append(context.workstation().activeWindowApplication()).append('\n');
        body.append("Display server: ").append(context.workstation().displayServer()).append('\n');
        body.append('\n');

        body.append("Evidence").append('\n');
        body.append("Evidence directory: ").append(context.evidence().evidenceDirectory()).append('\n');
        body.append("Webcam photo attached: ").append(context.evidence().webcamAttached()).append('\n');
        body.append("Screenshot attached: ").append(context.evidence().screenshotAttached()).append('\n');
        body.append("Metadata attached: ").append(context.evidence().metadataAttached()).append('\n');
        if (!context.warnings().isEmpty()) {
            body.append("Warnings: ").append(String.join(" | ", context.warnings())).append('\n');
        }

        return new AlertPayload(subject, body.toString(), evidenceBundle, verificationResult, context);
    }

    private WorkstationIncidentContext buildIncidentContext(String trigger,
                                                            org.jarvis.pccontrol.securitymonitoring.model.CapturedFrame frame,
                                                            VisionVerifyOwnerResponse verificationResult,
                                                            VisionScreenAnalysisResponse screenAnalysisResult,
                                                            MonitoringDecision decision,
                                                            org.jarvis.pccontrol.securitymonitoring.model.WorkstationMetadata workstationMetadata,
                                                            EvidenceBundle evidenceBundle,
                                                            List<String> warnings) {
        return WorkstationIncidentContext.of(
                trigger,
                frame,
                verificationResult,
                screenAnalysisResult,
                decision,
                workstationMetadata,
                evidenceBundle == null ? null : evidenceBundle.evidenceDirectory(),
                evidenceBundle == null ? null : evidenceBundle.webcamImagePath(),
                evidenceBundle == null ? null : evidenceBundle.screenshotPath(),
                evidenceBundle == null ? null : evidenceBundle.metadataFilePath(),
                warnings);
    }

    private static void appendContextLine(StringBuilder body,
                                          String value,
                                          String label) {
        if (value != null && !value.isBlank()) {
            body.append(label).append(": ").append(value).append('\n');
        }
    }

    private static VisionScreenAnalysisResponse unavailableScreenAnalysis(String message) {
        return new VisionScreenAnalysisResponse(
                false,
                VisionScreenCategory.UNAVAILABLE,
                message,
                null,
                false,
                null,
                false,
                java.util.Map.of());
    }

    private Instant nextAlertAllowedAt() {
        return runtimeState.get().lastAlertAt() == null
                ? null
                : runtimeState.get().lastAlertAt().plus(properties.getCooldownBetweenAlerts());
    }
}
