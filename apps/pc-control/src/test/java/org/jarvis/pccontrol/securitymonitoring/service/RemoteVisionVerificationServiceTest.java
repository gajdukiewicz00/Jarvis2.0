package org.jarvis.pccontrol.securitymonitoring.service;

import org.jarvis.common.vision.VisionFaceRegion;
import org.jarvis.common.vision.VisionVerificationOutcome;
import org.jarvis.common.vision.VisionVerifyOwnerRequest;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;
import org.jarvis.pccontrol.client.VisionServiceClient;
import org.jarvis.pccontrol.securitymonitoring.config.SecurityMonitoringProperties;
import org.jarvis.pccontrol.securitymonitoring.model.CapturedFrame;
import org.jarvis.pccontrol.securitymonitoring.service.impl.RemoteVisionVerificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RemoteVisionVerificationServiceTest {

    @Mock
    private VisionServiceClient visionServiceClient;

    @Test
    void returnsSharedVisionContractAndPropagatesRequestMetadata() {
        SecurityMonitoringProperties properties = new SecurityMonitoringProperties();
        RemoteVisionVerificationService service =
                new RemoteVisionVerificationService(visionServiceClient, properties);
        CapturedFrame frame = new CapturedFrame(
                new BufferedImage(4, 4, BufferedImage.TYPE_3BYTE_BGR),
                "webcam",
                "camera-0",
                Instant.parse("2026-03-27T12:00:00Z"));

        when(visionServiceClient.verifyOwner(anyString(), anyString(), any())).thenReturn(new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.OWNER,
                true,
                "baseline",
                "matched",
                0.93d,
                3,
                List.of(new VisionFaceRegion(1, 2, 3, 4)),
                Map.of("source", "pc-control")));

        VisionVerifyOwnerResponse result = service.verifyOwner(frame);
        ArgumentCaptor<VisionVerifyOwnerRequest> requestCaptor = ArgumentCaptor.forClass(VisionVerifyOwnerRequest.class);
        verify(visionServiceClient).verifyOwner(anyString(), anyString(), requestCaptor.capture());

        assertThat(result.outcome()).isEqualTo(VisionVerificationOutcome.OWNER);
        assertThat(result.detectedFaces()).hasSize(1);
        assertThat(result.diagnostics()).containsEntry("source", "pc-control");
        assertThat(result.diagnostics()).containsKeys("requestId", "correlationId");
        assertThat(requestCaptor.getValue().requestId()).isNotBlank();
        assertThat(requestCaptor.getValue().metadata()).containsEntry("provider", "webcam");
    }

    @Test
    void degradesGracefullyWhenVisionServiceIsUnavailable() {
        SecurityMonitoringProperties properties = new SecurityMonitoringProperties();
        properties.getVision().setSkipOnUnavailable(true);
        RemoteVisionVerificationService service =
                new RemoteVisionVerificationService(visionServiceClient, properties);
        CapturedFrame frame = new CapturedFrame(
                new BufferedImage(4, 4, BufferedImage.TYPE_3BYTE_BGR),
                "webcam",
                "camera-0",
                Instant.parse("2026-03-27T12:00:00Z"));

        when(visionServiceClient.verifyOwner(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("vision down"));

        VisionVerifyOwnerResponse result = service.verifyOwner(frame);

        assertThat(result.outcome()).isEqualTo(VisionVerificationOutcome.UNAVAILABLE);
        assertThat(result.operational()).isFalse();
        assertThat(result.diagnostics()).containsEntry("fallbackBehavior", "skip-on-unavailable");
    }

    @Test
    void treatsVisionFailureAsUnknownWhenSkipOnUnavailableIsDisabled() {
        SecurityMonitoringProperties properties = new SecurityMonitoringProperties();
        properties.getVision().setSkipOnUnavailable(false);
        RemoteVisionVerificationService service =
                new RemoteVisionVerificationService(visionServiceClient, properties);
        CapturedFrame frame = new CapturedFrame(
                new BufferedImage(4, 4, BufferedImage.TYPE_3BYTE_BGR),
                "webcam",
                "camera-0",
                Instant.parse("2026-03-27T12:00:00Z"));

        when(visionServiceClient.verifyOwner(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("vision down"));

        VisionVerifyOwnerResponse result = service.verifyOwner(frame);

        assertThat(result.outcome()).isEqualTo(VisionVerificationOutcome.UNKNOWN);
        assertThat(result.operational()).isTrue();
        assertThat(result.diagnostics()).containsEntry("fallbackBehavior", "treat-as-unknown");
    }
}
