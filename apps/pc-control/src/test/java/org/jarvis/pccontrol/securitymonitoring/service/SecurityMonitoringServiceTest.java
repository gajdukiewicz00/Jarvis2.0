package org.jarvis.pccontrol.securitymonitoring.service;

import org.jarvis.common.vision.VisionScreenAnalysisResponse;
import org.jarvis.common.vision.VisionScreenCategory;
import org.jarvis.common.vision.VisionVerificationOutcome;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;
import org.jarvis.pccontrol.securitymonitoring.config.SecurityMonitoringProperties;
import org.jarvis.pccontrol.securitymonitoring.model.CapturedFrame;
import org.jarvis.pccontrol.securitymonitoring.model.EvidenceBundle;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringCheckReport;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringDecision;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringDecisionState;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringRuntimeState;
import org.jarvis.pccontrol.securitymonitoring.model.ScreenObservation;
import org.jarvis.pccontrol.securitymonitoring.model.WebcamCaptureResult;
import org.jarvis.pccontrol.securitymonitoring.model.WorkstationIncidentContext;
import org.jarvis.pccontrol.securitymonitoring.service.impl.EmailAlertDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityMonitoringServiceTest {

    @Mock
    private WebcamFrameSource webcamFrameSource;
    @Mock
    private VisionVerificationService visionVerificationService;
    @Mock
    private ScreenAnalysisService screenAnalysisService;
    @Mock
    private SecurityMonitoringPolicy securityMonitoringPolicy;
    @Mock
    private EvidenceCollector evidenceCollector;
    @Mock
    private AlertDispatcher alertDispatcher;
    @Mock
    private JavaMailSender mailSender;

    private SecurityMonitoringService securityMonitoringService;

    @BeforeEach
    void setUp() {
        securityMonitoringService = new SecurityMonitoringService(
                new SecurityMonitoringProperties(),
                webcamFrameSource,
                visionVerificationService,
                screenAnalysisService,
                securityMonitoringPolicy,
                evidenceCollector,
                alertDispatcher);
    }

    @Test
    void dispatchesAlertWhenPolicyTriggersAndCarriesScreenContext() throws Exception {
        BufferedImage frameImage = new BufferedImage(32, 32, BufferedImage.TYPE_3BYTE_BGR);
        BufferedImage screenshotImage = new BufferedImage(64, 64, BufferedImage.TYPE_3BYTE_BGR);
        CapturedFrame frame = new CapturedFrame(frameImage, "test", "camera-0",
                Instant.parse("2026-03-27T12:00:00Z"));
        VisionVerifyOwnerResponse verificationResult = new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.UNKNOWN,
                true,
                "verifier",
                "below threshold",
                0.41d,
                2,
                List.of(),
                Map.of(
                        "identitySignalState", "UNKNOWN_CONFIRMED",
                        "livenessPassed", "false",
                        "livenessConfidence", "0.21"));
        VisionScreenAnalysisResponse screenAnalysis = new VisionScreenAnalysisResponse(
                true,
                VisionScreenCategory.TERMINAL,
                "screen looks sensitive",
                0.81d,
                true,
                0.88d,
                true,
                Map.of());
        MonitoringDecision decision = new MonitoringDecision(
                MonitoringDecisionState.ALERT_TRIGGERED,
                false,
                true,
                false,
                "high_risk_temporal_threshold_reached",
                "HIGH",
                92,
                84,
                Instant.parse("2026-03-27T12:00:00Z"),
                Instant.parse("2026-03-27T12:10:00Z"),
                new MonitoringRuntimeState(
                        3,
                        3,
                        2,
                        84,
                        "UNKNOWN_CONFIRMED",
                        "TERMINAL",
                        Instant.parse("2026-03-27T12:00:00Z"),
                        Instant.parse("2026-03-27T12:00:00Z")));
        EvidenceBundle evidenceBundle = new EvidenceBundle(
                Instant.parse("2026-03-27T12:00:00Z"),
                Path.of("/tmp/evidence"),
                Path.of("/tmp/evidence/webcam.jpg"),
                Path.of("/tmp/evidence/desktop.png"),
                Path.of("/tmp/evidence/metadata.json"),
                null,
                screenAnalysis,
                92,
                "HIGH",
                List.of());

        when(webcamFrameSource.captureFrame()).thenReturn(new WebcamCaptureResult(true, "test", "ok", frame));
        when(visionVerificationService.verifyOwner(frame)).thenReturn(verificationResult);
        when(screenAnalysisService.observe(frame)).thenReturn(new ScreenObservation(screenshotImage, screenAnalysis, List.of()));
        when(securityMonitoringPolicy.evaluate(
                verificationResult,
                screenAnalysis,
                MonitoringRuntimeState.initial(),
                Instant.parse("2026-03-27T12:00:00Z"))).thenReturn(decision);
        when(evidenceCollector.collect(eq("manual"), eq(frame), eq(verificationResult), ArgumentMatchers.any(), eq(decision), ArgumentMatchers.anyList()))
                .thenReturn(evidenceBundle);

        MonitoringCheckReport report = securityMonitoringService.runCheck("manual");

        assertThat(report.decision().alertTriggered()).isTrue();
        assertThat(report.evidenceBundle()).isEqualTo(evidenceBundle);
        assertThat(report.screenAnalysisResult().category()).isEqualTo(VisionScreenCategory.TERMINAL);
        verify(evidenceCollector).collect(eq("manual"), eq(frame), eq(verificationResult), ArgumentMatchers.any(), eq(decision), ArgumentMatchers.anyList());
        ArgumentCaptor<org.jarvis.pccontrol.securitymonitoring.model.AlertPayload> alertCaptor =
                ArgumentCaptor.forClass(org.jarvis.pccontrol.securitymonitoring.model.AlertPayload.class);
        verify(alertDispatcher).dispatch(alertCaptor.capture());
        assertThat(alertCaptor.getValue().subject()).contains("[HIGH]");
        assertThat(alertCaptor.getValue().message()).contains("Trigger: manual");
        assertThat(alertCaptor.getValue().message()).contains("Decision state: ALERT_TRIGGERED");
        assertThat(alertCaptor.getValue().message()).contains("Category: TERMINAL");
        assertThat(alertCaptor.getValue().message()).contains("Screenshot attached: true");
        assertThat(alertCaptor.getValue().incidentContext().decision().state()).isEqualTo("ALERT_TRIGGERED");
    }

    @Test
    void ownerConfirmedObservationDoesNotAlert() throws Exception {
        CapturedFrame frame = new CapturedFrame(
                new BufferedImage(32, 32, BufferedImage.TYPE_3BYTE_BGR),
                "test",
                "camera-0",
                Instant.parse("2026-03-27T12:00:00Z"));
        VisionVerifyOwnerResponse verificationResult = new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.OWNER,
                true,
                "verifier",
                "matched",
                0.92d,
                2,
                List.of(),
                Map.of("identitySignalState", "OWNER_CONFIRMED"));
        VisionScreenAnalysisResponse screenAnalysis = new VisionScreenAnalysisResponse(
                true,
                VisionScreenCategory.MEDIA,
                "media",
                0.62d,
                false,
                0.18d,
                false,
                Map.of());
        MonitoringDecision decision = new MonitoringDecision(
                MonitoringDecisionState.OWNER_CONFIRMED,
                false,
                false,
                false,
                "owner_verified",
                "LOW",
                0,
                0,
                Instant.parse("2026-03-27T12:00:00Z"),
                null,
                MonitoringRuntimeState.initial());

        when(webcamFrameSource.captureFrame()).thenReturn(new WebcamCaptureResult(true, "test", "ok", frame));
        when(visionVerificationService.verifyOwner(frame)).thenReturn(verificationResult);
        when(screenAnalysisService.observe(frame)).thenReturn(new ScreenObservation(
                new BufferedImage(64, 64, BufferedImage.TYPE_3BYTE_BGR),
                screenAnalysis,
                List.of()));
        when(securityMonitoringPolicy.evaluate(
                verificationResult,
                screenAnalysis,
                MonitoringRuntimeState.initial(),
                Instant.parse("2026-03-27T12:00:00Z"))).thenReturn(decision);

        MonitoringCheckReport report = securityMonitoringService.runCheck("manual");

        assertThat(report.decision().state()).isEqualTo(MonitoringDecisionState.OWNER_CONFIRMED);
        assertThat(report.decision().alertTriggered()).isFalse();
        assertThat(report.incidentContext().decision().state()).isEqualTo("OWNER_CONFIRMED");
        assertThat(report.incidentContext().identity().outcome()).isEqualTo("OWNER");
        verify(evidenceCollector, never()).collect(
                ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyList());
        verify(alertDispatcher, never()).dispatch(ArgumentMatchers.any());
    }

    @Test
    void skipsAlertPipelineWhenWebcamCaptureFails() throws Exception {
        when(webcamFrameSource.captureFrame())
                .thenReturn(new WebcamCaptureResult(false, "test", "camera unavailable", null));

        MonitoringCheckReport report = securityMonitoringService.runCheck("manual");

        assertThat(report.decision().skipped()).isTrue();
        assertThat(report.incidentContext().schemaVersion()).isEqualTo("jarvis-workstation-incident-v1");
        assertThat(report.incidentContext().trigger()).isEqualTo("manual");
        assertThat(report.incidentContext().decision().state()).isEqualTo("UNAVAILABLE");
        assertThat(report.incidentContext().identity().outcome()).isEqualTo("UNAVAILABLE");
        assertThat(report.incidentContext().screen().category()).isEqualTo("UNAVAILABLE");
        assertThat(report.incidentContext().evidence().webcamAttached()).isFalse();
        assertThat(report.incidentContext().evidence().screenshotAttached()).isFalse();
        assertThat(report.incidentContext().evidence().metadataAttached()).isFalse();
        verify(visionVerificationService, never()).verifyOwner(ArgumentMatchers.any());
        verify(screenAnalysisService, never()).observe(ArgumentMatchers.any());
        verify(evidenceCollector, never()).collect(
                ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyList());
        verify(alertDispatcher, never()).dispatch(ArgumentMatchers.any());
    }

    @Test
    void carriesScreenshotFailureAsDegradedObservationWithoutAlerting() throws Exception {
        CapturedFrame frame = new CapturedFrame(
                new BufferedImage(32, 32, BufferedImage.TYPE_3BYTE_BGR),
                "test",
                "camera-0",
                Instant.parse("2026-03-27T12:00:00Z"));
        VisionVerifyOwnerResponse verificationResult = new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.UNKNOWN,
                true,
                "verifier",
                "below threshold",
                0.52d,
                2,
                List.of(),
                Map.of("identitySignalState", "LOW_CONFIDENCE"));
        VisionScreenAnalysisResponse unavailableScreen = new VisionScreenAnalysisResponse(
                false,
                VisionScreenCategory.UNAVAILABLE,
                "Screenshot capture failed: headless",
                null,
                false,
                null,
                false,
                Map.of("screenCaptureOperational", "false"));
        MonitoringDecision decision = new MonitoringDecision(
                MonitoringDecisionState.DEGRADED,
                false,
                false,
                false,
                "low_confidence_screen_unavailable",
                "LOW",
                22,
                18,
                Instant.parse("2026-03-27T12:00:00Z"),
                null,
                new MonitoringRuntimeState(0, 0, 0, 18, "LOW_CONFIDENCE", "UNAVAILABLE",
                        Instant.parse("2026-03-27T12:00:00Z"), null));

        when(webcamFrameSource.captureFrame()).thenReturn(new WebcamCaptureResult(true, "test", "ok", frame));
        when(visionVerificationService.verifyOwner(frame)).thenReturn(verificationResult);
        when(screenAnalysisService.observe(frame)).thenReturn(new ScreenObservation(
                null,
                unavailableScreen,
                List.of("Screenshot capture failed: headless")));
        when(securityMonitoringPolicy.evaluate(
                verificationResult,
                unavailableScreen,
                MonitoringRuntimeState.initial(),
                Instant.parse("2026-03-27T12:00:00Z"))).thenReturn(decision);

        MonitoringCheckReport report = securityMonitoringService.runCheck("manual");

        assertThat(report.decision().state()).isEqualTo(MonitoringDecisionState.DEGRADED);
        assertThat(report.incidentContext().decision().state()).isEqualTo("DEGRADED");
        assertThat(report.incidentContext().screen().category()).isEqualTo("UNAVAILABLE");
        assertThat(report.incidentContext().evidence().screenshotAttached()).isFalse();
        assertThat(report.warnings()).anyMatch(message -> message.contains("Screenshot capture failed"));
        verify(evidenceCollector, never()).collect(
                ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyList());
        verify(alertDispatcher, never()).dispatch(ArgumentMatchers.any());
    }

    @Test
    void collectsEvidenceEvenWhenEmailDeliveryIsDisabled() throws Exception {
        SecurityMonitoringProperties properties = new SecurityMonitoringProperties();
        EmailAlertDispatcher disabledDispatcher = new EmailAlertDispatcher(mailSender, properties);
        SecurityMonitoringService serviceWithDisabledEmail = new SecurityMonitoringService(
                properties,
                webcamFrameSource,
                visionVerificationService,
                screenAnalysisService,
                securityMonitoringPolicy,
                evidenceCollector,
                disabledDispatcher);

        BufferedImage frameImage = new BufferedImage(32, 32, BufferedImage.TYPE_3BYTE_BGR);
        CapturedFrame frame = new CapturedFrame(frameImage, "test", "camera-0",
                Instant.parse("2026-03-27T12:00:00Z"));
        VisionVerifyOwnerResponse verificationResult = new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.UNKNOWN,
                true,
                "verifier",
                "below threshold",
                0.36d,
                2,
                List.of(),
                Map.of("identitySignalState", "UNKNOWN_CONFIRMED"));
        VisionScreenAnalysisResponse screenAnalysis = new VisionScreenAnalysisResponse(
                true,
                VisionScreenCategory.DOCUMENT,
                "document looks sensitive",
                0.84d,
                true,
                0.88d,
                true,
                Map.of());
        MonitoringDecision decision = new MonitoringDecision(
                MonitoringDecisionState.ALERT_TRIGGERED,
                false,
                true,
                false,
                "high_risk_temporal_threshold_reached",
                "HIGH",
                90,
                85,
                Instant.parse("2026-03-27T12:00:00Z"),
                Instant.parse("2026-03-27T12:10:00Z"),
                new MonitoringRuntimeState(3, 3, 2, 85, "UNKNOWN_CONFIRMED", "DOCUMENT",
                        Instant.parse("2026-03-27T12:00:00Z"), Instant.parse("2026-03-27T12:00:00Z")));
        EvidenceBundle evidenceBundle = new EvidenceBundle(
                Instant.parse("2026-03-27T12:00:00Z"),
                Files.createTempDirectory("jarvis-evidence"),
                Path.of("/tmp/evidence/webcam.jpg"),
                Path.of("/tmp/evidence/desktop.png"),
                Path.of("/tmp/evidence/metadata.json"),
                null,
                screenAnalysis,
                90,
                "HIGH",
                List.of());

        when(webcamFrameSource.captureFrame()).thenReturn(new WebcamCaptureResult(true, "test", "ok", frame));
        when(visionVerificationService.verifyOwner(frame)).thenReturn(verificationResult);
        when(screenAnalysisService.observe(frame)).thenReturn(new ScreenObservation(
                new BufferedImage(64, 64, BufferedImage.TYPE_3BYTE_BGR),
                screenAnalysis,
                List.of()));
        when(securityMonitoringPolicy.evaluate(
                verificationResult,
                screenAnalysis,
                MonitoringRuntimeState.initial(),
                Instant.parse("2026-03-27T12:00:00Z"))).thenReturn(decision);
        when(evidenceCollector.collect(eq("manual"), eq(frame), eq(verificationResult), ArgumentMatchers.any(), eq(decision), ArgumentMatchers.anyList()))
                .thenReturn(evidenceBundle);

        MonitoringCheckReport report = serviceWithDisabledEmail.runCheck("manual");

        assertThat(report.evidenceBundle()).isEqualTo(evidenceBundle);
        assertThat(report.incidentContext().evidence().screenshotAttached()).isTrue();
        verify(evidenceCollector).collect(eq("manual"), eq(frame), eq(verificationResult), ArgumentMatchers.any(), eq(decision), ArgumentMatchers.anyList());
        verify(mailSender, never()).send(ArgumentMatchers.any(jakarta.mail.internet.MimeMessage.class));
    }
}
