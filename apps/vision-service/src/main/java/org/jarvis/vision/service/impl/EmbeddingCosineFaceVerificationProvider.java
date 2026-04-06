package org.jarvis.vision.service.impl;

import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.FaceAlignmentService;
import org.jarvis.vision.service.FaceEmbeddingEncoder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingCosineFaceVerificationProvider extends AbstractEmbeddingCosineFaceVerificationProvider {

    private static final String PROVIDER = "embedding-cosine-mvp";

    public EmbeddingCosineFaceVerificationProvider(
            VisionServiceProperties properties,
            @Qualifier("blockGradientFaceEmbeddingEncoder") FaceEmbeddingEncoder faceEmbeddingEncoder,
            FaceAlignmentService faceAlignmentService,
            OwnerReferenceEmbeddingCache ownerReferenceEmbeddingCache) {
        super(
                PROVIDER,
                "mvp-handcrafted-embedding",
                properties,
                faceEmbeddingEncoder,
                faceAlignmentService,
                ownerReferenceEmbeddingCache);
    }
}
