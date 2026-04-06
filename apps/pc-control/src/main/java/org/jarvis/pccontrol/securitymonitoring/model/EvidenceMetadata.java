package org.jarvis.pccontrol.securitymonitoring.model;

import org.jarvis.pccontrol.model.DesktopSystemInfo;

import java.util.List;
import java.util.Map;

public record EvidenceMetadata(
        String schemaVersion,
        WorkstationIncidentContext incidentContext,
        String capturedAt,
        String webcamProvider,
        String webcamDevice,
        Verification verification,
        Screen screen,
        IncidentDecision incidentDecision,
        Workstation workstation,
        List<String> warnings) {

    public EvidenceMetadata {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? "jarvis-security-evidence-v1"
                : schemaVersion;
        incidentContext = incidentContext == null ? WorkstationIncidentContext.of(
                "unknown", null, null, null, null, null, null, null, null, null, warnings) : incidentContext;
        capturedAt = capturedAt == null ? "" : capturedAt;
        webcamProvider = webcamProvider == null ? "" : webcamProvider;
        webcamDevice = webcamDevice == null ? "" : webcamDevice;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public record Verification(
            String outcome,
            boolean operational,
            String provider,
            String message,
            Double similarity,
            int referenceImageCount,
            List<?> detectedFaces,
            Map<String, String> diagnostics) {

        public Verification {
            outcome = outcome == null ? "" : outcome;
            provider = provider == null ? "" : provider;
            message = message == null ? "" : message;
            detectedFaces = detectedFaces == null ? List.of() : List.copyOf(detectedFaces);
            diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
        }
    }

    public record Screen(
            boolean screenshotAttached,
            String screenshotSource,
            boolean analysisOperational,
            String analysisMessage,
            String category,
            Double categoryConfidence,
            boolean sensitive,
            Double sensitiveConfidence,
            boolean ocrReady,
            Map<String, String> diagnostics) {

        public Screen {
            screenshotSource = screenshotSource == null ? "" : screenshotSource;
            analysisMessage = analysisMessage == null ? "" : analysisMessage;
            category = category == null ? "" : category;
            diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
        }
    }

    public record IncidentDecision(
            String state,
            boolean skipped,
            boolean alertTriggered,
            boolean cooldownActive,
            String reason,
            String severity,
            int observationRiskScore,
            int rollingRiskScore,
            String evaluatedAt,
            String nextAlertAllowedAt,
            RuntimeState nextState) {

        public IncidentDecision {
            state = state == null ? "" : state;
            reason = reason == null ? "" : reason;
            severity = severity == null ? "" : severity;
            evaluatedAt = evaluatedAt == null ? "" : evaluatedAt;
            nextAlertAllowedAt = nextAlertAllowedAt == null ? "" : nextAlertAllowedAt;
        }
    }

    public record RuntimeState(
            int consecutiveUnknownDetections,
            int consecutiveSuspiciousObservations,
            int consecutiveHighRiskObservations,
            int rollingRiskScore,
            String lastIdentitySignalState,
            String lastScreenCategory,
            String lastCheckAt,
            String lastAlertAt) {

        public RuntimeState {
            lastIdentitySignalState = lastIdentitySignalState == null ? "" : lastIdentitySignalState;
            lastScreenCategory = lastScreenCategory == null ? "" : lastScreenCategory;
            lastCheckAt = lastCheckAt == null ? "" : lastCheckAt;
            lastAlertAt = lastAlertAt == null ? "" : lastAlertAt;
        }
    }

    public record Workstation(
            DesktopSystemInfo systemInfo,
            String activeWindowTitle,
            String activeWindowApplication,
            String username,
            Map<String, String> runtimeMetadata) {

        public Workstation {
            activeWindowTitle = activeWindowTitle == null ? "" : activeWindowTitle;
            activeWindowApplication = activeWindowApplication == null ? "" : activeWindowApplication;
            username = username == null ? "" : username;
            runtimeMetadata = runtimeMetadata == null ? Map.of() : Map.copyOf(runtimeMetadata);
        }
    }
}
