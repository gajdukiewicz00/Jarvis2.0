package org.jarvis.vision.service;

import java.awt.image.BufferedImage;
import java.util.Map;

public interface FaceAlignmentService {

    String providerId();

    FaceAlignmentResult align(BufferedImage faceImage);

    default boolean isAvailable() {
        return true;
    }

    default String availabilityMessage() {
        return "";
    }

    default String cacheKey() {
        return providerId();
    }

    default Map<String, String> statusDetails() {
        return Map.of(
                "alignmentProvider", providerId(),
                "alignmentAvailable", String.valueOf(isAvailable()));
    }
}
