package org.jarvis.vision.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.vision.service.FaceAlignmentService;
import org.jarvis.vision.service.FaceDetectionProvider;
import org.jarvis.vision.service.FaceEmbeddingEncoder;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class OwnerReferenceEmbeddingCache {

    private final OwnerReferenceFaceLoader ownerReferenceFaceLoader;
    private final Map<String, CachedSnapshot> cache = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastInvalidatedAt = new AtomicReference<>();
    private final AtomicReference<String> lastInvalidationReason = new AtomicReference<>("");
    private final AtomicReference<Instant> lastPrewarmedAt = new AtomicReference<>();

    public OwnerReferenceEmbeddingCache(OwnerReferenceFaceLoader ownerReferenceFaceLoader) {
        this.ownerReferenceFaceLoader = ownerReferenceFaceLoader;
    }

    public ReferenceEmbeddingSnapshot getReferenceEmbeddings(FaceDetectionProvider faceDetectionProvider,
                                                             FaceAlignmentService faceAlignmentService,
                                                             FaceEmbeddingEncoder faceEmbeddingEncoder) {
        String cacheKey = cacheKey(faceDetectionProvider, faceAlignmentService, faceEmbeddingEncoder);
        String fingerprint = fingerprint();

        CachedSnapshot existing = cache.get(cacheKey);
        if (existing != null && existing.fingerprint().equals(fingerprint)) {
            return existing.toSnapshot(cacheKey, true, false, lastInvalidatedAt.get(), lastInvalidationReason.get(),
                    lastPrewarmedAt.get());
        }

        synchronized (this) {
            existing = cache.get(cacheKey);
            if (existing != null && existing.fingerprint().equals(fingerprint)) {
                return existing.toSnapshot(cacheKey, true, false, lastInvalidatedAt.get(), lastInvalidationReason.get(),
                        lastPrewarmedAt.get());
            }

            if (!faceEmbeddingEncoder.isAvailable()) {
                CachedSnapshot unavailable = new CachedSnapshot(
                        fingerprint,
                        List.of(),
                        Instant.now(),
                        ownerReferenceFaceLoader.listReferenceFiles().size(),
                        faceEmbeddingEncoder.availabilityMessage(),
                        faceAlignmentService == null ? "" : faceAlignmentService.providerId());
                cache.put(cacheKey, unavailable);
                return unavailable.toSnapshot(cacheKey, false, false, lastInvalidatedAt.get(), lastInvalidationReason.get(),
                        lastPrewarmedAt.get());
            }

            List<OwnerReferenceFaceLoader.ReferenceFace> referenceFaces = faceAlignmentService == null
                    ? ownerReferenceFaceLoader.loadReferenceFaces(faceDetectionProvider)
                    : ownerReferenceFaceLoader.loadReferenceFaces(faceDetectionProvider, faceAlignmentService);
            List<ReferenceEmbedding> embeddings = new ArrayList<>();
            for (OwnerReferenceFaceLoader.ReferenceFace referenceFace : referenceFaces) {
                try {
                    embeddings.add(new ReferenceEmbedding(
                            referenceFace.sourcePath(),
                            faceEmbeddingEncoder.encode(referenceFace.faceImage())));
                } catch (Exception exception) {
                    log.warn("Skipping reference embedding for {}: {}", referenceFace.sourcePath(), exception.getMessage());
                }
            }

            CachedSnapshot rebuilt = new CachedSnapshot(
                    fingerprint,
                    List.copyOf(embeddings),
                    Instant.now(),
                    referenceFaces.size(),
                    embeddings.isEmpty() ? "No reference embeddings cached" : "",
                    faceAlignmentService == null ? "" : faceAlignmentService.providerId());
            cache.put(cacheKey, rebuilt);
            return rebuilt.toSnapshot(cacheKey, false, false, lastInvalidatedAt.get(), lastInvalidationReason.get(),
                    lastPrewarmedAt.get());
        }
    }

    public ReferenceEmbeddingSnapshot prewarm(FaceDetectionProvider faceDetectionProvider,
                                              FaceAlignmentService faceAlignmentService,
                                              FaceEmbeddingEncoder faceEmbeddingEncoder) {
        ReferenceEmbeddingSnapshot snapshot = getReferenceEmbeddings(faceDetectionProvider, faceAlignmentService,
                faceEmbeddingEncoder);
        lastPrewarmedAt.set(Instant.now());
        return new ReferenceEmbeddingSnapshot(
                snapshot.embeddings(),
                snapshot.loaded(),
                snapshot.cacheHit(),
                snapshot.stale(),
                snapshot.sourceFileCount(),
                snapshot.loadedAt(),
                snapshot.cacheKey(),
                snapshot.alignmentProvider(),
                snapshot.message(),
                snapshot.lastInvalidatedAt(),
                snapshot.lastInvalidationReason(),
                lastPrewarmedAt.get());
    }

    public ReferenceEmbeddingSnapshot status(FaceDetectionProvider faceDetectionProvider,
                                             FaceAlignmentService faceAlignmentService,
                                             FaceEmbeddingEncoder faceEmbeddingEncoder) {
        String cacheKey = cacheKey(faceDetectionProvider, faceAlignmentService, faceEmbeddingEncoder);
        String currentFingerprint = fingerprint();
        CachedSnapshot snapshot = cache.get(cacheKey);
        if (snapshot == null) {
            return new ReferenceEmbeddingSnapshot(
                    List.of(),
                    false,
                    false,
                    false,
                    ownerReferenceFaceLoader.listReferenceFiles().size(),
                    null,
                    cacheKey,
                    faceAlignmentService == null ? "" : faceAlignmentService.providerId(),
                    "Cache cold",
                    lastInvalidatedAt.get(),
                    lastInvalidationReason.get(),
                    lastPrewarmedAt.get());
        }
        boolean stale = !snapshot.fingerprint().equals(currentFingerprint);
        return snapshot.toSnapshot(cacheKey, false, stale, lastInvalidatedAt.get(), lastInvalidationReason.get(),
                lastPrewarmedAt.get());
    }

    public void invalidateAll() {
        invalidateAll("explicit");
    }

    public void invalidateAll(String reason) {
        cache.clear();
        lastInvalidatedAt.set(Instant.now());
        lastInvalidationReason.set(reason == null ? "explicit" : reason);
    }

    private String fingerprint() {
        StringBuilder builder = new StringBuilder();
        for (Path path : ownerReferenceFaceLoader.listReferenceFiles()) {
            builder.append(path.toAbsolutePath()).append(':');
            try {
                builder.append(Files.size(path)).append(':')
                        .append(Files.getLastModifiedTime(path).toMillis());
            } catch (Exception exception) {
                builder.append("unreadable");
            }
            builder.append('|');
        }
        return builder.toString();
    }

    private static String cacheKey(FaceDetectionProvider faceDetectionProvider,
                                   FaceAlignmentService faceAlignmentService,
                                   FaceEmbeddingEncoder faceEmbeddingEncoder) {
        String detectorKey = faceDetectionProvider == null ? "unknown-detector" : faceDetectionProvider.cacheKey();
        String alignmentKey = faceAlignmentService == null ? "alignment-disabled" : faceAlignmentService.cacheKey();
        return detectorKey + "::" + alignmentKey + "::" + faceEmbeddingEncoder.cacheKey();
    }

    public record ReferenceEmbedding(Path sourcePath, double[] embedding) {
    }

    private record CachedSnapshot(
            String fingerprint,
            List<ReferenceEmbedding> embeddings,
            Instant loadedAt,
            int sourceFileCount,
            String message,
            String alignmentProvider) {

        ReferenceEmbeddingSnapshot toSnapshot(String cacheKey,
                                              boolean cacheHit,
                                              boolean stale,
                                              Instant lastInvalidatedAt,
                                              String lastInvalidationReason,
                                              Instant lastPrewarmedAt) {
            return new ReferenceEmbeddingSnapshot(
                    embeddings,
                    !embeddings.isEmpty(),
                    cacheHit,
                    stale,
                    sourceFileCount,
                    loadedAt,
                    cacheKey,
                    alignmentProvider,
                    message,
                    lastInvalidatedAt,
                    lastInvalidationReason,
                    lastPrewarmedAt);
        }
    }

    public record ReferenceEmbeddingSnapshot(
            List<ReferenceEmbedding> embeddings,
            boolean loaded,
            boolean cacheHit,
            boolean stale,
            int sourceFileCount,
            Instant loadedAt,
            String cacheKey,
            String alignmentProvider,
            String message,
            Instant lastInvalidatedAt,
            String lastInvalidationReason,
            Instant lastPrewarmedAt) {

        public Map<String, String> diagnostics() {
            Map<String, String> diagnostics = new LinkedHashMap<>();
            diagnostics.put("referenceEmbeddingCacheLoaded", String.valueOf(loaded));
            diagnostics.put("referenceEmbeddingCacheHit", String.valueOf(cacheHit));
            diagnostics.put("referenceEmbeddingCacheStale", String.valueOf(stale));
            diagnostics.put("referenceEmbeddingCachedCount", String.valueOf(embeddings.size()));
            diagnostics.put("referenceEmbeddingSourceFileCount", String.valueOf(sourceFileCount));
            diagnostics.put("referenceEmbeddingCacheState", state());
            diagnostics.put("referenceEmbeddingCacheKey", cacheKey == null ? "" : cacheKey);
            diagnostics.put("referenceEmbeddingAlignmentProvider", alignmentProvider == null ? "" : alignmentProvider);
            diagnostics.put("referenceEmbeddingInvalidationPolicy",
                    "invalidate-on-enroll-or-reference-fingerprint-change");
            if (loadedAt != null) {
                diagnostics.put("referenceEmbeddingCacheLoadedAt", loadedAt.toString());
            }
            if (lastInvalidatedAt != null) {
                diagnostics.put("referenceEmbeddingLastInvalidatedAt", lastInvalidatedAt.toString());
            }
            if (lastInvalidationReason != null && !lastInvalidationReason.isBlank()) {
                diagnostics.put("referenceEmbeddingLastInvalidationReason", lastInvalidationReason);
            }
            if (lastPrewarmedAt != null) {
                diagnostics.put("referenceEmbeddingLastPrewarmedAt", lastPrewarmedAt.toString());
            }
            if (message != null && !message.isBlank()) {
                diagnostics.put("referenceEmbeddingCacheMessage", message);
            }
            return diagnostics;
        }

        private String state() {
            if (stale) {
                return "stale";
            }
            if (loaded) {
                return "warm";
            }
            return sourceFileCount == 0 ? "empty" : "cold";
        }
    }
}
