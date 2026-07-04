package org.jarvis.memory.cv;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.memory.service.EmbeddingClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Persists {@link ScreenContextEvent}s into {@code screen_context_observation}.
 *
 * <ul>
 *   <li>Idempotent on a derived key (userId + capturedAt + screenshotPath), so
 *       a redelivered Kafka record never double-persists.</li>
 *   <li>Raw screenshot bytes are stored only when enabled AND the file is
 *       readable by this process AND within the size cap — otherwise just the
 *       path reference is kept (honest, no silent data loss).</li>
 *   <li>Embedding is best-effort: a missing/failing embedding-service leaves a
 *       null embedding and still persists the row.</li>
 * </ul>
 */
@Slf4j
@Service
public class ScreenContextPersistenceService {

    static final String EMBEDDING_MODEL = "multilingual-e5-small";

    private final ScreenContextObservationRepository repository;
    private final ScreenContextProperties properties;
    private final EmbeddingClient embeddingClient;
    private final MeterRegistry meterRegistry;

    public ScreenContextPersistenceService(ScreenContextObservationRepository repository,
                                           ScreenContextProperties properties,
                                           EmbeddingClient embeddingClient,
                                           MeterRegistry meterRegistry) {
        this.repository = repository;
        this.properties = properties;
        this.embeddingClient = embeddingClient;
        this.meterRegistry = meterRegistry;
    }

    public enum Outcome { PERSISTED, DUPLICATE, FAILED }

    public Outcome persist(ScreenContextEvent event) {
        if (event == null) {
            count("failure", "null_event");
            return Outcome.FAILED;
        }
        String idempotencyKey = idempotencyKey(event);
        try {
            if (repository.existsByIdempotencyKey(idempotencyKey)) {
                log.debug("screen-context duplicate ignored key={}", idempotencyKey);
                count("duplicate", null);
                return Outcome.DUPLICATE;
            }

            ScreenContextObservationEntity entity = toEntity(event, idempotencyKey);
            attachScreenshot(entity, event);
            attachEmbedding(entity, event);

            repository.save(entity);
            log.info("screen-context persisted id={} user={} chars={} tags={} ui={} obj={} hasImage={} embedded={}",
                    entity.getId(), entity.getUserId(),
                    entity.getOcrText() == null ? 0 : entity.getOcrText().length(),
                    entity.getSemanticTags().size(),
                    entity.getUiElements().size(), entity.getObjects().size(),
                    entity.getScreenshotBytes() != null, entity.getEmbedding() != null);
            count("persisted", null);
            return Outcome.PERSISTED;
        } catch (RuntimeException ex) {
            log.error("screen-context persist failed key={}: {}", idempotencyKey, ex.getMessage(), ex);
            count("failure", "persist_error");
            return Outcome.FAILED;
        }
    }

    ScreenContextObservationEntity toEntity(ScreenContextEvent event, String idempotencyKey) {
        return ScreenContextObservationEntity.builder()
                .id(UUID.randomUUID())
                .idempotencyKey(idempotencyKey)
                .userId(event.userId())
                .capturedAt(event.capturedAt())
                .receivedAt(Instant.now())
                .durationMs(event.durationMs())
                .displayServer(event.displayServer())
                .activeWindowTitle(event.activeWindowTitle())
                .activeProcessName(event.activeProcessName())
                .semanticTags(event.semanticTags() == null ? List.of() : event.semanticTags())
                .ocrText(event.ocrText())
                .ocrBlocks(event.analysis() == null || event.analysis().blocks() == null
                        ? List.of() : event.analysis().blocks())
                .uiElements(event.uiElements() == null ? List.of() : event.uiElements())
                .objects(event.objects() == null ? List.of() : event.objects())
                .screenshotPath(event.screenshotPath())
                .engine(event.analysis() == null ? null : event.analysis().engine())
                .ocrLanguage(event.analysis() == null ? null : event.analysis().language())
                .success(event.success())
                .error(event.error())
                .build();
    }

    private void attachScreenshot(ScreenContextObservationEntity entity, ScreenContextEvent event) {
        if (!properties.isStoreRawScreenshot()) {
            return;
        }
        String pathStr = event.screenshotPath();
        if (pathStr == null || pathStr.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(pathStr);
            if (!Files.isReadable(path)) {
                log.debug("screenshot not readable here (path-only kept): {}", pathStr);
                return;
            }
            long size = Files.size(path);
            if (size > properties.getMaxScreenshotBytes()) {
                log.info("screenshot {} exceeds cap ({} > {}); storing path only",
                        pathStr, size, properties.getMaxScreenshotBytes());
                return;
            }
            entity.setScreenshotBytes(Files.readAllBytes(path));
        } catch (Exception ex) {
            log.warn("failed reading screenshot {} (path-only kept): {}", pathStr, ex.getMessage());
        }
    }

    private void attachEmbedding(ScreenContextObservationEntity entity, ScreenContextEvent event) {
        if (!properties.isEmbed()) {
            return;
        }
        String text = event.ocrText();
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            List<Float> vector = embeddingClient.embed(text, "screen-ctx-" + entity.getId());
            float[] primitive = toPrimitiveArray(vector);
            if (primitive != null) {
                entity.setEmbedding(primitive);
                entity.setEmbeddingModel(EMBEDDING_MODEL);
            }
        } catch (RuntimeException ex) {
            // Embedding-service down / unreachable — degrade gracefully.
            log.warn("embedding skipped for screen-context {} (row still persisted): {}",
                    entity.getId(), ex.getMessage());
            count("embed_skip", "embedding_unavailable");
        }
    }

    static float[] toPrimitiveArray(List<Float> vector) {
        if (vector == null || vector.isEmpty()) {
            return null;
        }
        float[] values = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            Float v = vector.get(i);
            values[i] = v == null ? 0.0f : v;
        }
        return values;
    }

    static String idempotencyKey(ScreenContextEvent event) {
        String raw = (event.userId() == null ? "" : event.userId())
                + "|" + (event.capturedAt() == null ? "" : event.capturedAt())
                + "|" + (event.screenshotPath() == null ? "" : event.screenshotPath());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            // SHA-256 is always available; fall back defensively.
            return Integer.toHexString(raw.hashCode());
        }
    }

    private void count(String outcome, String reason) {
        Counter.builder("jarvis_memory_cv_persist_total")
                .tags(Tags.of("outcome", outcome))
                .register(meterRegistry)
                .increment();
        if (reason != null) {
            Counter.builder("jarvis_memory_cv_persist_failures_total")
                    .tags(Tags.of("reason", reason))
                    .register(meterRegistry)
                    .increment();
        }
    }
}
