package org.jarvis.vision.service;

import org.jarvis.common.vision.VisionFaceRegion;

import java.awt.image.BufferedImage;
import java.util.List;

public interface FaceDetectionProvider {

    String providerId();

    DetectionResult detectFaces(BufferedImage image);

    default String cacheKey() {
        return providerId();
    }

    record DetectionResult(
            boolean operational,
            String provider,
            String message,
            List<VisionFaceRegion> faces) {

        public DetectionResult {
            provider = provider == null ? "" : provider;
            message = message == null ? "" : message;
            faces = faces == null ? List.of() : List.copyOf(faces);
        }
    }
}
