package org.jarvis.vision.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.vision.VisionConfigStatusResponse;
import org.jarvis.common.vision.VisionHealthResponse;
import org.jarvis.common.vision.VisionOwnerReferenceEnrollRequest;
import org.jarvis.common.vision.VisionOwnerReferenceEnrollResponse;
import org.jarvis.common.vision.VisionScreenAnalysisRequest;
import org.jarvis.common.vision.VisionScreenAnalysisResponse;
import org.jarvis.common.vision.VisionVerifyOwnerDebugResponse;
import org.jarvis.common.vision.VisionVerifyOwnerRequest;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;
import org.jarvis.vision.service.OwnerReferenceService;
import org.jarvis.vision.service.VisionScreenAnalysisService;
import org.jarvis.vision.service.VisionStatusService;
import org.jarvis.vision.service.VisionVerificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vision")
@Slf4j
@RequiredArgsConstructor
public class VisionController {

    private final VisionVerificationService visionVerificationService;
    private final VisionScreenAnalysisService visionScreenAnalysisService;
    private final VisionStatusService visionStatusService;
    private final OwnerReferenceService ownerReferenceService;

    @PostMapping("/face/verify-owner")
    public ResponseEntity<VisionVerifyOwnerResponse> verifyOwner(
            @Valid @RequestBody VisionVerifyOwnerRequest request) {
        log.info("Vision verify request: requestId={}, source={}, format={}",
                request.requestId(), request.source(), request.imageFormat());
        VisionVerifyOwnerResponse response = visionVerificationService.verifyOwner(request);
        log.info("Vision verify response: requestId={}, outcome={}, provider={}, operational={}",
                request.requestId(), response.outcome(), response.provider(), response.operational());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/face/verify-owner/debug")
    public ResponseEntity<VisionVerifyOwnerDebugResponse> verifyOwnerDebug(
            @Valid @RequestBody VisionVerifyOwnerRequest request) {
        log.info("Vision debug verify request: requestId={}, source={}, format={}",
                request.requestId(), request.source(), request.imageFormat());
        VisionVerifyOwnerDebugResponse response = visionVerificationService.verifyOwnerDebug(request);
        log.info("Vision debug verify response: requestId={}, decision={}, provider={}, operational={}",
                request.requestId(),
                response.finalDecision(),
                response.verification().provider(),
                response.verification().operational());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/screen/analyze")
    public ResponseEntity<VisionScreenAnalysisResponse> analyzeScreen(
            @Valid @RequestBody VisionScreenAnalysisRequest request) {
        log.info("Vision screen analysis request: requestId={}, source={}, format={}",
                request.requestId(), request.source(), request.imageFormat());
        VisionScreenAnalysisResponse response = visionScreenAnalysisService.analyze(request);
        log.info("Vision screen analysis response: requestId={}, category={}, operational={}, sensitive={}",
                request.requestId(), response.category(), response.operational(), response.sensitive());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    public ResponseEntity<VisionHealthResponse> health() {
        return ResponseEntity.ok(visionStatusService.health());
    }

    @GetMapping("/config/status")
    public ResponseEntity<VisionConfigStatusResponse> configStatus() {
        return ResponseEntity.ok(visionStatusService.configStatus());
    }

    @PostMapping("/owner-reference/enroll")
    public ResponseEntity<VisionOwnerReferenceEnrollResponse> enroll(
            @Valid @RequestBody VisionOwnerReferenceEnrollRequest request) throws Exception {
        log.info("Vision enrollment request: requestId={}, label={}", request.requestId(), request.label());
        VisionOwnerReferenceEnrollResponse response = ownerReferenceService.enroll(request);
        log.info("Vision enrollment response: requestId={}, stored={}, file={}",
                request.requestId(), response.stored(), response.storedFilename());
        return ResponseEntity.ok(response);
    }
}
