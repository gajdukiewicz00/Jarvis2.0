package org.jarvis.vision.service;

import java.awt.image.BufferedImage;
import java.util.Map;

public interface FaceLivenessAssessor {

    String providerId();

    FaceLivenessAssessment assess(BufferedImage faceImage);

    default boolean isAvailable() {
        return true;
    }

    default String availabilityMessage() {
        return "";
    }

    default Map<String, String> statusDetails() {
        return Map.of(
                "livenessProvider", providerId(),
                "livenessAvailable", String.valueOf(isAvailable()));
    }
}
