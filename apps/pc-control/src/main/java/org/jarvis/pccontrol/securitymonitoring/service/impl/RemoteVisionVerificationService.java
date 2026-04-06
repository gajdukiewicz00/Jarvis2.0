package org.jarvis.pccontrol.securitymonitoring.service.impl;

import feign.FeignException;
import feign.RetryableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.vision.VisionVerificationOutcome;
import org.jarvis.common.vision.VisionVerifyOwnerRequest;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;
import org.jarvis.pccontrol.client.VisionServiceClient;
import org.jarvis.pccontrol.securitymonitoring.config.SecurityMonitoringProperties;
import org.jarvis.pccontrol.securitymonitoring.model.CapturedFrame;
import org.jarvis.pccontrol.securitymonitoring.service.VisionVerificationService;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RemoteVisionVerificationService implements VisionVerificationService {

    private static final String PROVIDER = "vision-service";

    private final VisionServiceClient visionServiceClient;
    private final SecurityMonitoringProperties properties;

    @Override
    public VisionVerifyOwnerResponse verifyOwner(CapturedFrame frame) {
        String requestId = UUID.randomUUID().toString();
        String correlationId = currentCorrelationId(requestId);
        try {
            byte[] imageBytes = encodeJpeg(frame);
            VisionVerifyOwnerRequest request = new VisionVerifyOwnerRequest(
                    imageBytes,
                    "jpg",
                    "pc-control/webcam",
                    requestId,
                    Map.of(
                            "capturedAt", frame.capturedAt().toString(),
                            "provider", frame.provider(),
                            "device", frame.device(),
                            "correlationId", correlationId));

            log.info("Submitting webcam verification request to vision-service: requestId={}, correlationId={}, capturedAt={}",
                    requestId, correlationId, frame.capturedAt());
            VisionVerifyOwnerResponse response = visionServiceClient.verifyOwner(requestId, correlationId, request);
            return enrichResponse(response, requestId, correlationId);
        } catch (RetryableException exception) {
            log.warn("Vision service unavailable: requestId={}, correlationId={}, message={}",
                    requestId, correlationId, exception.getMessage());
            return availabilityFallback(requestId, correlationId, "retryable_exception", null, exception.getMessage());
        } catch (FeignException exception) {
            int status = exception.status();
            String statusText = status > 0 ? Integer.toString(status) : "unknown";
            if (status >= 400 && status < 500) {
                log.error("Vision service contract error: requestId={}, correlationId={}, status={}, message={}",
                        requestId, correlationId, statusText, exception.getMessage());
                return unavailableResult(
                        requestId,
                        correlationId,
                        "vision contract error: HTTP " + statusText,
                        Map.of(
                                "errorType", "http_client_error",
                                "httpStatus", statusText,
                                "fallbackBehavior", "skip"));
            }
            log.warn("Vision service server failure: requestId={}, correlationId={}, status={}, message={}",
                    requestId, correlationId, statusText, exception.getMessage());
            return availabilityFallback(requestId, correlationId, "http_server_error", statusText, exception.getMessage());
        } catch (Exception exception) {
            log.error("Unexpected vision verification failure: requestId={}, correlationId={}, message={}",
                    requestId, correlationId, exception.getMessage(), exception);
            return availabilityFallback(
                    requestId,
                    correlationId,
                    "unexpected_client_error",
                    null,
                    exception.getMessage());
        }
    }

    private static byte[] encodeJpeg(CapturedFrame frame) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            boolean encoded = ImageIO.write(frame.image(), "jpg", output);
            if (!encoded) {
                throw new IllegalStateException("Failed to encode webcam frame as jpg");
            }
            return output.toByteArray();
        }
    }

    private VisionVerifyOwnerResponse availabilityFallback(String requestId,
                                                           String correlationId,
                                                           String errorType,
                                                           String httpStatus,
                                                           String message) {
        Map<String, String> diagnostics = new LinkedHashMap<>();
        diagnostics.put("errorType", errorType);
        diagnostics.put("fallbackBehavior",
                properties.getVision().isSkipOnUnavailable() ? "skip-on-unavailable" : "treat-as-unknown");
        if (httpStatus != null) {
            diagnostics.put("httpStatus", httpStatus);
        }

        if (properties.getVision().isSkipOnUnavailable()) {
            return unavailableResult(requestId, correlationId, message, diagnostics);
        }
        diagnostics.put("remoteOperational", "false");
        diagnostics.put("requestId", requestId);
        diagnostics.put("correlationId", correlationId);
        return new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.UNKNOWN,
                true,
                PROVIDER,
                message,
                0.0d,
                0,
                java.util.List.of(),
                diagnostics);
    }

    private static VisionVerifyOwnerResponse unavailableResult(String requestId,
                                                               String correlationId,
                                                               String message,
                                                               Map<String, String> diagnosticsInput) {
        Map<String, String> diagnostics = new LinkedHashMap<>(diagnosticsInput);
        diagnostics.put("remoteOperational", "false");
        diagnostics.put("requestId", requestId);
        diagnostics.put("correlationId", correlationId);
        return new VisionVerifyOwnerResponse(
                VisionVerificationOutcome.UNAVAILABLE,
                false,
                PROVIDER,
                message,
                null,
                0,
                java.util.List.of(),
                diagnostics);
    }

    private static VisionVerifyOwnerResponse enrichResponse(VisionVerifyOwnerResponse response,
                                                            String requestId,
                                                            String correlationId) {
        Map<String, String> diagnostics = new LinkedHashMap<>(response.diagnostics());
        diagnostics.put("remoteOperational", String.valueOf(response.operational()));
        diagnostics.put("requestId", requestId);
        diagnostics.put("correlationId", correlationId);
        return new VisionVerifyOwnerResponse(
                response.outcome(),
                response.operational(),
                response.provider(),
                response.message(),
                response.similarity(),
                response.referenceImageCount(),
                response.detectedFaces(),
                diagnostics);
    }

    private static String currentCorrelationId(String requestId) {
        String correlationId = MDC.get("correlationId");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = MDC.get("traceId");
        }
        return correlationId == null || correlationId.isBlank() ? requestId : correlationId;
    }
}
