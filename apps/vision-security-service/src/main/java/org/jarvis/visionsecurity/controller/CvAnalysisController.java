package org.jarvis.visionsecurity.controller;

import lombok.RequiredArgsConstructor;
import org.jarvis.visionsecurity.model.AskScreenResult;
import org.jarvis.visionsecurity.model.CvAnalysisResult;
import org.jarvis.visionsecurity.model.ScreenContextResult;
import org.jarvis.visionsecurity.service.LocalCvService;
import org.jarvis.visionsecurity.service.cv.AskScreenCvService;
import org.jarvis.visionsecurity.service.cv.ScreenContextCvService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;

/**
 * HTTP entry-points for the local CV vertical slice:
 * <ul>
 *   <li>{@code POST /api/v1/vision-security/cv/analyze} — OCR a file or a fresh screenshot</li>
 *   <li>{@code POST /api/v1/vision-security/cv/screen-context} — wider screen-understanding capture</li>
 * </ul>
 *
 * Responses are structured JSON ({@link CvAnalysisResult} /
 * {@link ScreenContextResult}). No cloud APIs are called.
 */
@RestController
@RequestMapping("/api/v1/vision-security/cv")
@RequiredArgsConstructor
public class CvAnalysisController {

    private final LocalCvService cvService;
    private final ScreenContextCvService screenContextCvService;
    private final AskScreenCvService askScreenCvService;

    @PostMapping("/analyze")
    public ResponseEntity<CvAnalysisResult> analyze(@RequestBody(required = false) AnalyzeRequest request) {
        String source = request == null ? LocalCvService.SOURCE_FILE
                : LocalCvService.normalizeSource(request.source());
        if (LocalCvService.SOURCE_SCREENSHOT.equals(source)) {
            Path target = request != null && request.imagePath() != null && !request.imagePath().isBlank()
                    ? Path.of(request.imagePath())
                    : null;
            return ResponseEntity.ok(cvService.analyzeScreenshot(target));
        }
        if (request == null || request.imagePath() == null || request.imagePath().isBlank()) {
            throw new IllegalArgumentException("imagePath is required when source=file");
        }
        return ResponseEntity.ok(cvService.analyzeFile(Path.of(request.imagePath())));
    }

    @PostMapping("/screen-context")
    public ResponseEntity<ScreenContextResult> screenContext(
            Authentication authentication,
            @RequestBody(required = false) ScreenContextRequest request
    ) {
        Path target = request != null && request.imagePath() != null && !request.imagePath().isBlank()
                ? Path.of(request.imagePath())
                : null;
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(screenContextCvService.capture(userId, target));
    }

    @PostMapping("/ask-screen")
    public ResponseEntity<AskScreenResult> askScreen(
            Authentication authentication,
            @RequestBody AskScreenRequest request
    ) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        boolean hasImagePath = request.imagePath() != null && !request.imagePath().isBlank();
        boolean captureFresh = request.captureFreshScreenshot() == null
                ? true : request.captureFreshScreenshot();
        if (!hasImagePath && !captureFresh) {
            throw new IllegalArgumentException(
                    "captureFreshScreenshot=false requires an imagePath to analyze");
        }
        // An explicit imagePath always wins; otherwise a fresh screenshot is captured
        // (target=null tells the service to capture).
        Path target = hasImagePath ? Path.of(request.imagePath()) : null;
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(askScreenCvService.ask(userId, request.question(), target));
    }

    public record AnalyzeRequest(String source, String imagePath) {
    }

    public record ScreenContextRequest(String imagePath) {
    }

    /**
     * {@code captureFreshScreenshot} (default true) captures the live screen.
     * {@code imagePath}, when provided, analyzes that file instead and takes
     * precedence. Setting {@code captureFreshScreenshot=false} without an
     * {@code imagePath} is rejected.
     */
    public record AskScreenRequest(String question, Boolean captureFreshScreenshot, String imagePath) {
    }
}
