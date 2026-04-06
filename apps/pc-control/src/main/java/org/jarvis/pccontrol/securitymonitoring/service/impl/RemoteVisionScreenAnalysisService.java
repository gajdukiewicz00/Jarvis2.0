package org.jarvis.pccontrol.securitymonitoring.service.impl;

import feign.FeignException;
import feign.RetryableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.vision.VisionScreenAnalysisRequest;
import org.jarvis.common.vision.VisionScreenAnalysisResponse;
import org.jarvis.common.vision.VisionScreenCategory;
import org.jarvis.pccontrol.client.VisionServiceClient;
import org.jarvis.pccontrol.securitymonitoring.config.SecurityMonitoringProperties;
import org.jarvis.pccontrol.securitymonitoring.model.CapturedFrame;
import org.jarvis.pccontrol.securitymonitoring.model.ScreenObservation;
import org.jarvis.pccontrol.securitymonitoring.service.ScreenAnalysisService;
import org.jarvis.pccontrol.securitymonitoring.service.ScreenshotCaptureService;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RemoteVisionScreenAnalysisService implements ScreenAnalysisService {

    private final VisionServiceClient visionServiceClient;
    private final ScreenshotCaptureService screenshotCaptureService;
    private final SecurityMonitoringProperties properties;

    @Override
    public ScreenObservation observe(CapturedFrame frame) {
        if (!properties.getScreenAnalysis().isEnabled()) {
            return new ScreenObservation(
                    null,
                    new VisionScreenAnalysisResponse(
                            false,
                            VisionScreenCategory.UNAVAILABLE,
                            "Screen analysis disabled",
                            null,
                            false,
                            null,
                            false,
                            Map.of(
                                    "screenAnalysisEnabled", "false",
                                    "screenAnalysisMethod", "disabled")),
                    List.of());
        }

        BufferedImage screenshot;
        List<String> warnings = new ArrayList<>();
        Instant screenshotCapturedAt;
        try {
            screenshot = screenshotCaptureService.captureScreenshot();
            screenshotCapturedAt = Instant.now();
        } catch (Exception exception) {
            warnings.add("Screenshot capture failed: " + exception.getMessage());
            return new ScreenObservation(
                    null,
                    unavailableResult("Screenshot capture failed: " + exception.getMessage(), Map.of(
                            "screenCaptureOperational", "false",
                            "screenCaptureMode", "robot",
                            "fallbackBehavior", "screen-analysis-unavailable")),
                    warnings);
        }

        String requestId = UUID.randomUUID().toString();
        String correlationId = currentCorrelationId(requestId);
        try {
            byte[] imageBytes = encodePng(screenshot);
            VisionScreenAnalysisRequest request = new VisionScreenAnalysisRequest(
                    imageBytes,
                    "png",
                    "pc-control/screenshot",
                    requestId,
                    Map.of(
                            "capturedAt", screenshotCapturedAt.toString(),
                            "webcamCapturedAt", frame.capturedAt().toString(),
                            "provider", frame.provider(),
                            "device", frame.device(),
                            "screenCaptureMode", "robot",
                            "correlationId", correlationId));
            VisionScreenAnalysisResponse response = visionServiceClient.analyzeScreen(requestId, correlationId, request);
            return new ScreenObservation(
                    screenshot,
                    enrichResponse(response, requestId, correlationId, screenshotCapturedAt),
                    warnings);
        } catch (RetryableException exception) {
            warnings.add("Screen analysis unavailable: " + exception.getMessage());
            return new ScreenObservation(screenshot, unavailableResult(exception.getMessage(), Map.of(
                    "errorType", "retryable_exception",
                    "fallbackBehavior", "screen-analysis-unavailable",
                    "screenCaptureOperational", "true",
                    "screenCaptureMode", "robot",
                    "requestId", requestId,
                    "correlationId", correlationId)), warnings);
        } catch (FeignException exception) {
            String status = exception.status() > 0 ? Integer.toString(exception.status()) : "unknown";
            warnings.add("Screen analysis HTTP failure: " + status);
            return new ScreenObservation(screenshot, unavailableResult(
                    "Screen analysis HTTP failure: " + status, Map.of(
                            "errorType", exception.status() >= 400 && exception.status() < 500
                                    ? "http_client_error" : "http_server_error",
                            "httpStatus", status,
                            "fallbackBehavior", "screen-analysis-unavailable",
                            "screenCaptureOperational", "true",
                            "screenCaptureMode", "robot",
                            "requestId", requestId,
                            "correlationId", correlationId)), warnings);
        } catch (Exception exception) {
            warnings.add("Screen analysis failed: " + exception.getMessage());
            return new ScreenObservation(screenshot, unavailableResult(exception.getMessage(), Map.of(
                    "errorType", "unexpected_client_error",
                    "fallbackBehavior", "screen-analysis-unavailable",
                    "screenCaptureOperational", "true",
                    "screenCaptureMode", "robot",
                    "requestId", requestId,
                    "correlationId", correlationId)), warnings);
        }
    }

    private static byte[] encodePng(BufferedImage screenshot) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            boolean encoded = ImageIO.write(screenshot, "png", output);
            if (!encoded) {
                throw new IllegalStateException("Failed to encode screenshot as png");
            }
            return output.toByteArray();
        }
    }

    private static VisionScreenAnalysisResponse unavailableResult(String message,
                                                                  Map<String, String> diagnosticsInput) {
        Map<String, String> diagnostics = new LinkedHashMap<>(diagnosticsInput);
        diagnostics.putIfAbsent("remoteOperational", "false");
        return new VisionScreenAnalysisResponse(
                false,
                VisionScreenCategory.UNAVAILABLE,
                message,
                null,
                false,
                null,
                false,
                diagnostics);
    }

    private static VisionScreenAnalysisResponse enrichResponse(VisionScreenAnalysisResponse response,
                                                               String requestId,
                                                               String correlationId,
                                                               Instant screenshotCapturedAt) {
        Map<String, String> diagnostics = new LinkedHashMap<>(response.diagnostics());
        diagnostics.put("remoteOperational", String.valueOf(response.operational()));
        diagnostics.put("screenCaptureOperational", "true");
        diagnostics.put("screenCaptureMode", "robot");
        diagnostics.put("screenCapturedAt", screenshotCapturedAt.toString());
        diagnostics.put("requestId", requestId);
        diagnostics.put("correlationId", correlationId);
        return new VisionScreenAnalysisResponse(
                response.operational(),
                response.category(),
                response.message(),
                response.categoryConfidence(),
                response.sensitive(),
                response.sensitiveConfidence(),
                response.ocrReady(),
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
