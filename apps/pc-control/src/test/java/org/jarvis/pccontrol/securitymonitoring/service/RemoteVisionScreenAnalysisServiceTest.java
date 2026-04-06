package org.jarvis.pccontrol.securitymonitoring.service;

import org.jarvis.common.vision.VisionScreenAnalysisRequest;
import org.jarvis.common.vision.VisionScreenAnalysisResponse;
import org.jarvis.common.vision.VisionScreenCategory;
import org.jarvis.pccontrol.client.VisionServiceClient;
import org.jarvis.pccontrol.securitymonitoring.config.SecurityMonitoringProperties;
import org.jarvis.pccontrol.securitymonitoring.model.CapturedFrame;
import org.jarvis.pccontrol.securitymonitoring.model.ScreenObservation;
import org.jarvis.pccontrol.securitymonitoring.service.impl.RemoteVisionScreenAnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RemoteVisionScreenAnalysisServiceTest {

    @Mock
    private VisionServiceClient visionServiceClient;
    @Mock
    private ScreenshotCaptureService screenshotCaptureService;

    @Test
    void returnsSharedScreenContractAndPropagatesScreenshotMetadata() throws Exception {
        SecurityMonitoringProperties properties = new SecurityMonitoringProperties();
        RemoteVisionScreenAnalysisService service =
                new RemoteVisionScreenAnalysisService(visionServiceClient, screenshotCaptureService, properties);
        CapturedFrame frame = new CapturedFrame(
                new BufferedImage(4, 4, BufferedImage.TYPE_3BYTE_BGR),
                "webcam",
                "camera-0",
                Instant.parse("2026-03-27T12:00:00Z"));
        BufferedImage screenshot = new BufferedImage(8, 8, BufferedImage.TYPE_3BYTE_BGR);

        when(screenshotCaptureService.captureScreenshot()).thenReturn(screenshot);
        when(visionServiceClient.analyzeScreen(anyString(), anyString(), any())).thenReturn(new VisionScreenAnalysisResponse(
                true,
                VisionScreenCategory.DOCUMENT,
                "document",
                0.75d,
                true,
                0.82d,
                true,
                Map.of("screenAnalysisMethod", "heuristic-foundation")));

        ScreenObservation result = service.observe(frame);
        ArgumentCaptor<VisionScreenAnalysisRequest> requestCaptor = ArgumentCaptor.forClass(VisionScreenAnalysisRequest.class);
        verify(visionServiceClient).analyzeScreen(anyString(), anyString(), requestCaptor.capture());

        assertThat(result.hasScreenshot()).isTrue();
        assertThat(result.analysisResult().category()).isEqualTo(VisionScreenCategory.DOCUMENT);
        assertThat(result.analysisResult().diagnostics()).containsKeys(
                "requestId",
                "correlationId",
                "screenCaptureOperational",
                "screenCaptureMode",
                "screenCapturedAt");
        assertThat(requestCaptor.getValue().source()).isEqualTo("pc-control/screenshot");
        assertThat(requestCaptor.getValue().metadata()).containsEntry("provider", "webcam");
    }

    @Test
    void degradesGracefullyWhenScreenshotCaptureFails() throws Exception {
        SecurityMonitoringProperties properties = new SecurityMonitoringProperties();
        RemoteVisionScreenAnalysisService service =
                new RemoteVisionScreenAnalysisService(visionServiceClient, screenshotCaptureService, properties);
        CapturedFrame frame = new CapturedFrame(
                new BufferedImage(4, 4, BufferedImage.TYPE_3BYTE_BGR),
                "webcam",
                "camera-0",
                Instant.parse("2026-03-27T12:00:00Z"));

        when(screenshotCaptureService.captureScreenshot()).thenThrow(new IllegalStateException("headless"));

        ScreenObservation result = service.observe(frame);

        assertThat(result.analysisResult().operational()).isFalse();
        assertThat(result.analysisResult().category()).isEqualTo(VisionScreenCategory.UNAVAILABLE);
        assertThat(result.analysisResult().diagnostics()).containsEntry("screenCaptureOperational", "false");
        assertThat(result.warnings()).anyMatch(message -> message.contains("Screenshot capture failed"));
    }
}
