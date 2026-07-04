package org.jarvis.memory.cv;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read API for persisted CV screen-context observations.
 *
 * <ul>
 *   <li>{@code GET /api/v1/memory/cv/screen-context/recent?userId=&limit=}
 *       — recent observations, newest first (no raw image bytes).</li>
 *   <li>{@code GET /api/v1/memory/cv/screen-context/{id}} — single row metadata.</li>
 * </ul>
 *
 * Auth is enforced at the api-gateway edge.
 */
@RestController
@RequestMapping("/api/v1/memory/cv/screen-context")
@RequiredArgsConstructor
public class ScreenContextController {

    private static final int MAX_LIMIT = 200;

    private final ScreenContextObservationRepository repository;

    @GetMapping("/recent")
    public List<ScreenContextView> recent(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        return repository.findRecent(userId, PageRequest.of(0, capped))
                .stream().map(ScreenContextView::of).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScreenContextView> byId(@PathVariable UUID id) {
        return repository.findById(id)
                .map(ScreenContextView::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Projection that never exposes raw screenshot bytes over the wire. */
    public record ScreenContextView(
            UUID id,
            String userId,
            Instant capturedAt,
            Instant receivedAt,
            String displayServer,
            String activeWindowTitle,
            String activeProcessName,
            List<String> semanticTags,
            String ocrText,
            List<Map<String, Object>> uiElements,
            List<Map<String, Object>> objects,
            String screenshotPath,
            boolean hasScreenshotBytes,
            boolean hasEmbedding,
            String engine,
            boolean success
    ) {
        static ScreenContextView of(ScreenContextObservationEntity e) {
            return new ScreenContextView(
                    e.getId(), e.getUserId(), e.getCapturedAt(), e.getReceivedAt(),
                    e.getDisplayServer(), e.getActiveWindowTitle(), e.getActiveProcessName(),
                    e.getSemanticTags(), e.getOcrText(), e.getUiElements(), e.getObjects(),
                    e.getScreenshotPath(),
                    e.getScreenshotBytes() != null && e.getScreenshotBytes().length > 0,
                    e.getEmbedding() != null && e.getEmbedding().length > 0,
                    e.getEngine(), e.isSuccess());
        }
    }
}
