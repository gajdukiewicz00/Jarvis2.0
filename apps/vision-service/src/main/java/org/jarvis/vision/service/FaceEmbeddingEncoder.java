package org.jarvis.vision.service;

import java.awt.image.BufferedImage;
import java.util.Map;

public interface FaceEmbeddingEncoder {

    String encoderId();

    double[] encode(BufferedImage faceImage);

    default boolean isAvailable() {
        return true;
    }

    default String availabilityMessage() {
        return "";
    }

    default String cacheKey() {
        return encoderId();
    }

    default Map<String, String> statusDetails() {
        return Map.of(
                "embeddingBackendConfigured", encoderId(),
                "embeddingBackendAvailable", String.valueOf(isAvailable()));
    }
}
