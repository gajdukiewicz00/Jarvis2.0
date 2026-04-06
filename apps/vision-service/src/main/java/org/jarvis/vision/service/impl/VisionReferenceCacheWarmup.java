package org.jarvis.vision.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.FaceAlignmentService;
import org.jarvis.vision.service.FaceDetectionProvider;
import org.jarvis.vision.service.FaceEmbeddingEncoder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class VisionReferenceCacheWarmup {

    private final VisionServiceProperties properties;
    private final List<FaceDetectionProvider> faceDetectionProviders;
    private final FaceAlignmentService faceAlignmentService;
    @Qualifier("openCvDnnFaceEmbeddingEncoder")
    private final FaceEmbeddingEncoder modelFaceEmbeddingEncoder;
    private final OwnerReferenceEmbeddingCache ownerReferenceEmbeddingCache;

    @EventListener(ApplicationReadyEvent.class)
    public void prewarmIfConfigured() {
        if (!properties.getReferenceCache().isPrewarmOnStartup()) {
            return;
        }
        if (!"embedding-cosine-model".equals(properties.getVerifierProvider())) {
            return;
        }

        FaceDetectionProvider detector = faceDetectionProviders.stream()
                .filter(provider -> provider.providerId().equals(properties.getDetectorProvider()))
                .findFirst()
                .orElse(null);
        if (detector == null) {
            log.warn("Skipping reference embedding cache prewarm because detector {} is unavailable",
                    properties.getDetectorProvider());
            return;
        }

        OwnerReferenceEmbeddingCache.ReferenceEmbeddingSnapshot snapshot = ownerReferenceEmbeddingCache.prewarm(
                detector,
                faceAlignmentService,
                modelFaceEmbeddingEncoder);
        log.info("Reference embedding cache prewarm finished: state={}, count={}, message={}",
                snapshot.diagnostics().get("referenceEmbeddingCacheState"),
                snapshot.embeddings().size(),
                snapshot.message());
    }
}
