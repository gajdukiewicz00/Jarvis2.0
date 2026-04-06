package org.jarvis.pccontrol.securitymonitoring.model;

import org.jarvis.common.vision.VisionScreenAnalysisResponse;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record WorkstationIncidentContext(
        String schemaVersion,
        String trigger,
        String capturedAt,
        Decision decision,
        Identity identity,
        Screen screen,
        Evidence evidence,
        Workstation workstation,
        List<String> warnings) {

    public WorkstationIncidentContext {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? "jarvis-workstation-incident-v1"
                : schemaVersion;
        trigger = trigger == null || trigger.isBlank() ? "unknown" : trigger;
        capturedAt = capturedAt == null ? "" : capturedAt;
        decision = decision == null ? Decision.empty() : decision;
        identity = identity == null ? Identity.empty() : identity;
        screen = screen == null ? Screen.empty() : screen;
        evidence = evidence == null ? Evidence.empty() : evidence;
        workstation = workstation == null ? Workstation.empty() : workstation;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static WorkstationIncidentContext of(String trigger,
                                                CapturedFrame frame,
                                                VisionVerifyOwnerResponse verificationResult,
                                                VisionScreenAnalysisResponse screenAnalysisResult,
                                                MonitoringDecision decision,
                                                WorkstationMetadata workstationMetadata,
                                                Path evidenceDirectory,
                                                Path webcamImagePath,
                                                Path screenshotPath,
                                                Path metadataFilePath,
                                                List<String> warnings) {
        String capturedAt = frame != null
                ? frame.capturedAt().toString()
                : decision != null && decision.evaluatedAt() != null ? decision.evaluatedAt().toString() : "";
        Map<String, String> verificationDiagnostics = verificationResult == null
                ? Map.of() : copyDiagnostics(verificationResult.diagnostics());
        Map<String, String> screenDiagnostics = screenAnalysisResult == null
                ? Map.of() : copyDiagnostics(screenAnalysisResult.diagnostics());

        String hostname = workstationMetadata == null || workstationMetadata.systemInfo() == null
                ? ""
                : workstationMetadata.systemInfo().hostname();
        String displayServer = workstationMetadata == null || workstationMetadata.systemInfo() == null
                ? ""
                : workstationMetadata.systemInfo().displayServer();

        return new WorkstationIncidentContext(
                "jarvis-workstation-incident-v1",
                trigger,
                capturedAt,
                decision == null ? Decision.empty() : new Decision(
                        decision.state().name(),
                        decision.skipped(),
                        decision.alertTriggered(),
                        decision.cooldownActive(),
                        decision.reason(),
                        decision.severity(),
                        decision.observationRiskScore(),
                        decision.rollingRiskScore(),
                        decision.evaluatedAt() == null ? "" : decision.evaluatedAt().toString(),
                        decision.nextAlertAllowedAt() == null ? "" : decision.nextAlertAllowedAt().toString()),
                verificationResult == null ? Identity.empty() : new Identity(
                        verificationResult.outcome().name(),
                        verificationResult.operational(),
                        verificationResult.provider(),
                        verificationResult.message(),
                        verificationResult.similarity(),
                        diagnostic(verificationDiagnostics, "identitySignalState"),
                        diagnostic(verificationDiagnostics, "identitySignalConfidence"),
                        diagnostic(verificationDiagnostics, "livenessAvailable"),
                        diagnostic(verificationDiagnostics, "livenessPassed"),
                        diagnostic(verificationDiagnostics, "livenessConfidence"),
                        verificationDiagnostics),
                screenAnalysisResult == null ? Screen.empty() : new Screen(
                        screenAnalysisResult.operational(),
                        screenAnalysisResult.message(),
                        screenAnalysisResult.category().name(),
                        screenAnalysisResult.categoryConfidence(),
                        screenAnalysisResult.sensitive(),
                        screenAnalysisResult.sensitiveConfidence(),
                        screenAnalysisResult.ocrReady(),
                        diagnostic(screenDiagnostics, "screenCaptureMode"),
                        diagnostic(screenDiagnostics, "screenCapturedAt"),
                        screenDiagnostics),
                new Evidence(
                        evidenceDirectory == null ? "" : evidenceDirectory.toString(),
                        webcamImagePath != null,
                        screenshotPath != null,
                        metadataFilePath != null),
                new Workstation(
                        hostname,
                        workstationMetadata == null ? "" : workstationMetadata.username(),
                        workstationMetadata == null ? "" : workstationMetadata.activeWindowTitle(),
                        workstationMetadata == null ? "" : workstationMetadata.activeWindowApplication(),
                        displayServer),
                warnings);
    }

    private static String diagnostic(Map<String, String> diagnostics, String key) {
        if (diagnostics == null) {
            return "";
        }
        String value = diagnostics.get(key);
        return value == null ? "" : value;
    }

    private static Map<String, String> copyDiagnostics(Map<String, String> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(diagnostics));
    }

    public record Decision(
            String state,
            boolean skipped,
            boolean alertTriggered,
            boolean cooldownActive,
            String reason,
            String severity,
            int observationRiskScore,
            int rollingRiskScore,
            String evaluatedAt,
            String nextAlertAllowedAt) {

        public Decision {
            state = state == null ? "" : state;
            reason = reason == null ? "" : reason;
            severity = severity == null ? "" : severity;
            evaluatedAt = evaluatedAt == null ? "" : evaluatedAt;
            nextAlertAllowedAt = nextAlertAllowedAt == null ? "" : nextAlertAllowedAt;
        }

        public static Decision empty() {
            return new Decision("", false, false, false, "", "", 0, 0, "", "");
        }
    }

    public record Identity(
            String outcome,
            boolean operational,
            String provider,
            String message,
            Double similarity,
            String identitySignalState,
            String identitySignalConfidence,
            String livenessAvailable,
            String livenessPassed,
            String livenessConfidence,
            Map<String, String> diagnostics) {

        public Identity {
            outcome = outcome == null ? "" : outcome;
            provider = provider == null ? "" : provider;
            message = message == null ? "" : message;
            identitySignalState = identitySignalState == null ? "" : identitySignalState;
            identitySignalConfidence = identitySignalConfidence == null ? "" : identitySignalConfidence;
            livenessAvailable = livenessAvailable == null ? "" : livenessAvailable;
            livenessPassed = livenessPassed == null ? "" : livenessPassed;
            livenessConfidence = livenessConfidence == null ? "" : livenessConfidence;
            diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
        }

        public static Identity empty() {
            return new Identity("", false, "", "", null, "", "", "", "", "", Map.of());
        }
    }

    public record Screen(
            boolean operational,
            String message,
            String category,
            Double categoryConfidence,
            boolean sensitive,
            Double sensitiveConfidence,
            boolean ocrReady,
            String captureMode,
            String capturedAt,
            Map<String, String> diagnostics) {

        public Screen {
            message = message == null ? "" : message;
            category = category == null ? "" : category;
            captureMode = captureMode == null ? "" : captureMode;
            capturedAt = capturedAt == null ? "" : capturedAt;
            diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
        }

        public static Screen empty() {
            return new Screen(false, "", "", null, false, null, false, "", "", Map.of());
        }
    }

    public record Evidence(
            String evidenceDirectory,
            boolean webcamAttached,
            boolean screenshotAttached,
            boolean metadataAttached) {

        public Evidence {
            evidenceDirectory = evidenceDirectory == null ? "" : evidenceDirectory;
        }

        public static Evidence empty() {
            return new Evidence("", false, false, false);
        }
    }

    public record Workstation(
            String hostname,
            String username,
            String activeWindowTitle,
            String activeWindowApplication,
            String displayServer) {

        public Workstation {
            hostname = hostname == null ? "" : hostname;
            username = username == null ? "" : username;
            activeWindowTitle = activeWindowTitle == null ? "" : activeWindowTitle;
            activeWindowApplication = activeWindowApplication == null ? "" : activeWindowApplication;
            displayServer = displayServer == null ? "" : displayServer;
        }

        public static Workstation empty() {
            return new Workstation("", "", "", "", "");
        }
    }
}
