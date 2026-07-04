package org.jarvis.vision.phase10;

import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 10 — desktop-agent capture boundary endpoint.
 *
 * <p>SPEC-1 splits CV: capture lives on the host (desktop agent), analysis
 * lives in {@code vision-security-service}. The agent posts metadata
 * (and Pass 2: a presigned-style {@code imageUri}) here when it captures
 * a frame; this endpoint validates demo-mode policy, emits a
 * {@code VISION_FRAME_CAPTURED} event, and returns a frame id the
 * caller can attach to subsequent OCR / face / incident requests.</p>
 *
 * <p>Pass 1 is metadata-only — no raw bytes traverse the cluster yet;
 * the existing {@code CameraCaptureService} on the host keeps its frames
 * locally. Pass 2 will add a binary-upload variant for off-host
 * re-analysis when the operator opts in.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/vision/frames")
@RequiredArgsConstructor
public class DesktopFrameIngestController {

    private final VisionEventEmitter emitter;
    private final DemoModeProperties demoMode;

    @PostMapping
    public ResponseEntity<FrameIngestResponse> ingest(@RequestBody FrameIngestRequest body) {
        if (body == null || body.captureType() == null || body.captureType().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (demoMode.isEnabled()) {
            log.warn("demo mode active — refusing frame ingest from agent={} user={}",
                    body.agentId(), body.userId());
            emitter.demoModeBlocked(body.agentId(), body.userId(),
                    "/api/v1/vision/frames");
            return ResponseEntity.status(403).body(FrameIngestResponse.refused(
                    demoMode.getReason()));
        }

        String frameId = "frame-" + UUID.randomUUID();
        Map<String, Object> extra = new HashMap<>();
        extra.put("frameId", frameId);
        if (body.imageUri() != null) extra.put("imageUri", body.imageUri());
        if (body.contextWindow() != null) extra.put("contextWindow", body.contextWindow());
        if (body.timestamp() != null) extra.put("capturedAt", body.timestamp().toString());

        emitter.frameCaptured(body.agentId(), body.userId(), body.captureType(), extra);
        log.info("frame captured agent={} user={} captureType={} frameId={}",
                body.agentId(), body.userId(), body.captureType(), frameId);

        return ResponseEntity.accepted().body(FrameIngestResponse.accepted(frameId));
    }

    public record FrameIngestRequest(
            String agentId,
            String userId,
            @NotBlank String captureType,   // "webcam" | "screen" | "window" | "document"
            String imageUri,                // optional file:// or s3-style URI
            String contextWindow,           // active-window title / app name
            Instant timestamp
    ) {}

    public record FrameIngestResponse(String frameId, String status, String reason) {
        public static FrameIngestResponse accepted(String frameId) {
            return new FrameIngestResponse(frameId, "accepted", null);
        }
        public static FrameIngestResponse refused(String reason) {
            return new FrameIngestResponse(null, "refused", reason);
        }
    }
}
