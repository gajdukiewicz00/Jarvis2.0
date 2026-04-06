package org.jarvis.pccontrol.securitymonitoring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.jarvis.common.vision.VisionScreenAnalysisResponse;
import org.jarvis.common.vision.VisionScreenCategory;
import org.jarvis.common.vision.VisionVerificationOutcome;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;
import org.jarvis.pccontrol.model.DesktopSystemInfo;
import org.jarvis.pccontrol.securitymonitoring.config.SecurityMonitoringProperties;
import org.jarvis.pccontrol.securitymonitoring.model.CapturedFrame;
import org.jarvis.pccontrol.securitymonitoring.model.EvidenceBundle;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringDecision;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringDecisionState;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringRuntimeState;
import org.jarvis.pccontrol.securitymonitoring.model.ScreenObservation;
import org.jarvis.pccontrol.securitymonitoring.model.WorkstationMetadata;
import org.jarvis.pccontrol.securitymonitoring.service.impl.DefaultEvidenceCollector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultEvidenceCollectorTest {

    @Mock
    private ScreenshotCaptureService screenshotCaptureService;
    @Mock
    private WorkstationMetadataProvider workstationMetadataProvider;

    @TempDir
    Path tempDir;

    @Test
    void writesWebcamScreenshotAndMetadataWithScreenAnalysisContext() throws Exception {
        SecurityMonitoringProperties properties = new SecurityMonitoringProperties();
        properties.setEvidenceDirectory(tempDir);

        DefaultEvidenceCollector collector = new DefaultEvidenceCollector(
                properties,
                screenshotCaptureService,
                workstationMetadataProvider,
                new ObjectMapper());

        CapturedFrame frame = new CapturedFrame(
                new BufferedImage(16, 16, BufferedImage.TYPE_3BYTE_BGR),
                "webcam",
                "camera-0",
                Instant.parse("2026-03-27T12:00:00Z"));
        VisionVerifyOwnerResponse verificationResult = new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.UNKNOWN,
                true,
                "vision-service",
                "unknown",
                0.38d,
                2,
                List.of(),
                Map.of("identitySignalState", "UNKNOWN_CONFIRMED"));
        VisionScreenAnalysisResponse screenAnalysis = new VisionScreenAnalysisResponse(
                true,
                VisionScreenCategory.TERMINAL,
                "terminal",
                0.78d,
                true,
                0.91d,
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
                82,
                Instant.parse("2026-03-27T12:00:00Z"),
                Instant.parse("2026-03-27T12:10:00Z"),
                MonitoringRuntimeState.initial());
        WorkstationMetadata metadata = new WorkstationMetadata(
                new DesktopSystemInfo("linux", "ubuntu", "6.8", "x86_64", "host-1", "gnome", "wayland", List.of("firefox")),
                "Secrets.txt",
                "firefox",
                "kwaqa",
                Map.of("pcControlStubMode", "false"));
        when(workstationMetadataProvider.collect()).thenReturn(metadata);

        EvidenceBundle bundle = collector.collect(
                "manual",
                frame,
                verificationResult,
                new ScreenObservation(new BufferedImage(20, 20, BufferedImage.TYPE_3BYTE_BGR), screenAnalysis, List.of()),
                decision,
                List.of("test warning"));

        assertThat(Files.exists(bundle.webcamImagePath())).isTrue();
        assertThat(Files.exists(bundle.screenshotPath())).isTrue();
        assertThat(Files.exists(bundle.metadataFilePath())).isTrue();
        assertThat(bundle.screenAnalysisResult().category()).isEqualTo(VisionScreenCategory.TERMINAL);
        assertThat(bundle.incidentSeverity()).isEqualTo("HIGH");

        String metadataJson = Files.readString(bundle.metadataFilePath());
        var metadataNode = new ObjectMapper().readTree(metadataJson);
        assertThat(metadataNode.path("schemaVersion").asText()).isEqualTo("jarvis-security-evidence-v1");
        assertThat(metadataNode.path("incidentContext").path("trigger").asText()).isEqualTo("manual");
        assertThat(metadataNode.path("screen").path("screenshotSource").asText()).isEqualTo("screen-analysis-capture");
        assertThat(metadataNode.path("incidentDecision").path("state").asText()).isEqualTo("ALERT_TRIGGERED");
        verify(screenshotCaptureService, never()).captureScreenshot();
    }

    @Test
    void recordsWarningWhenFallbackScreenshotCaptureFails() throws Exception {
        SecurityMonitoringProperties properties = new SecurityMonitoringProperties();
        properties.setEvidenceDirectory(tempDir);

        DefaultEvidenceCollector collector = new DefaultEvidenceCollector(
                properties,
                screenshotCaptureService,
                workstationMetadataProvider,
                new ObjectMapper());

        CapturedFrame frame = new CapturedFrame(
                new BufferedImage(16, 16, BufferedImage.TYPE_3BYTE_BGR),
                "webcam",
                "camera-0",
                Instant.parse("2026-03-27T12:00:00Z"));
        VisionVerifyOwnerResponse verificationResult = new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.UNKNOWN,
                true,
                "vision-service",
                "unknown",
                0.38d,
                2,
                List.of(),
                Map.of("identitySignalState", "UNKNOWN_CONFIRMED"));
        MonitoringDecision decision = new MonitoringDecision(
                MonitoringDecisionState.ALERT_TRIGGERED,
                false,
                true,
                false,
                "high_risk_temporal_threshold_reached",
                "HIGH",
                90,
                82,
                Instant.parse("2026-03-27T12:00:00Z"),
                Instant.parse("2026-03-27T12:10:00Z"),
                MonitoringRuntimeState.initial());
        when(workstationMetadataProvider.collect()).thenReturn(new WorkstationMetadata(
                new DesktopSystemInfo("linux", "ubuntu", "6.8", "x86_64", "host-1", "gnome", "wayland", List.of("firefox")),
                "Secrets.txt",
                "firefox",
                "kwaqa",
                Map.of("pcControlStubMode", "false")));
        when(screenshotCaptureService.captureScreenshot()).thenThrow(new IllegalStateException("headless"));

        EvidenceBundle bundle = collector.collect(
                "manual",
                frame,
                verificationResult,
                new ScreenObservation(null, null, List.of()),
                decision,
                List.of());

        assertThat(bundle.screenshotPath()).isNull();
        assertThat(bundle.metadataFilePath()).isNotNull();
        assertThat(bundle.warnings()).anyMatch(message -> message.contains("Screenshot capture failed"));

        String metadataJson = Files.readString(bundle.metadataFilePath());
        var metadataNode = new ObjectMapper().readTree(metadataJson);
        assertThat(metadataNode.path("incidentContext").path("screen").path("category").asText()).isEqualTo("UNAVAILABLE");
        assertThat(metadataNode.path("incidentContext").path("evidence").path("screenshotAttached").asBoolean()).isFalse();
    }

    @Test
    void survivesMetadataWriteFailureAndReturnsWarnings() throws Exception {
        SecurityMonitoringProperties properties = new SecurityMonitoringProperties();
        properties.setEvidenceDirectory(tempDir);

        ObjectMapper failingMapper = mock(ObjectMapper.class);
        ObjectWriter failingWriter = mock(ObjectWriter.class);
        when(failingMapper.writerWithDefaultPrettyPrinter()).thenReturn(failingWriter);
        doThrow(new RuntimeException("disk full")).when(failingWriter).writeValue(any(java.io.File.class), any());

        DefaultEvidenceCollector collector = new DefaultEvidenceCollector(
                properties,
                screenshotCaptureService,
                workstationMetadataProvider,
                failingMapper);

        CapturedFrame frame = new CapturedFrame(
                new BufferedImage(16, 16, BufferedImage.TYPE_3BYTE_BGR),
                "webcam",
                "camera-0",
                Instant.parse("2026-03-27T12:00:00Z"));
        VisionVerifyOwnerResponse verificationResult = new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.UNKNOWN,
                true,
                "vision-service",
                "unknown",
                0.38d,
                2,
                List.of(),
                Map.of("identitySignalState", "UNKNOWN_CONFIRMED"));
        VisionScreenAnalysisResponse screenAnalysis = new VisionScreenAnalysisResponse(
                true,
                VisionScreenCategory.TERMINAL,
                "terminal",
                0.78d,
                true,
                0.91d,
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
                82,
                Instant.parse("2026-03-27T12:00:00Z"),
                Instant.parse("2026-03-27T12:10:00Z"),
                MonitoringRuntimeState.initial());
        when(workstationMetadataProvider.collect()).thenReturn(new WorkstationMetadata(
                new DesktopSystemInfo("linux", "ubuntu", "6.8", "x86_64", "host-1", "gnome", "wayland", List.of("firefox")),
                "Secrets.txt",
                "firefox",
                "kwaqa",
                Map.of("pcControlStubMode", "false")));

        EvidenceBundle bundle = collector.collect(
                "manual",
                frame,
                verificationResult,
                new ScreenObservation(new BufferedImage(20, 20, BufferedImage.TYPE_3BYTE_BGR), screenAnalysis, List.of()),
                decision,
                List.of());

        assertThat(bundle.webcamImagePath()).isNotNull();
        assertThat(bundle.screenshotPath()).isNotNull();
        assertThat(bundle.metadataFilePath()).isNull();
        assertThat(bundle.warnings()).anyMatch(message -> message.contains("Metadata write failed"));
    }
}
