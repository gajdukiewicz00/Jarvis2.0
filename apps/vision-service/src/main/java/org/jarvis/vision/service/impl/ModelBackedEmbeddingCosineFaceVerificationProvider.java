package org.jarvis.vision.service.impl;

import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.FaceAlignmentService;
import org.jarvis.vision.service.FaceEmbeddingEncoder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ModelBackedEmbeddingCosineFaceVerificationProvider extends AbstractEmbeddingCosineFaceVerificationProvider {

    private static final String PROVIDER = "embedding-cosine-model";

    public ModelBackedEmbeddingCosineFaceVerificationProvider(
            VisionServiceProperties properties,
            @Qualifier("openCvDnnFaceEmbeddingEncoder") FaceEmbeddingEncoder faceEmbeddingEncoder,
            FaceAlignmentService faceAlignmentService,
            OwnerReferenceEmbeddingCache ownerReferenceEmbeddingCache) {
        super(
                PROVIDER,
                "model-backed-embedding",
                properties,
                faceEmbeddingEncoder,
                faceAlignmentService,
                ownerReferenceEmbeddingCache);
    }
}
