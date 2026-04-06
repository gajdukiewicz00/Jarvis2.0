package org.jarvis.vision.service;

import org.jarvis.common.vision.VisionVerifyOwnerResponse;

import java.awt.image.BufferedImage;
import java.util.Map;

public interface FaceVerificationProvider {

    String providerId();

    VisionVerifyOwnerResponse verifyOwner(BufferedImage image,
                                          FaceDetectionProvider faceDetectionProvider,
                                          FaceDetectionProvider.DetectionResult detectionResult);

    default boolean isAvailable() {
        return true;
    }

    default String availabilityMessage() {
        return "";
    }

    default Map<String, String> statusDetails(FaceDetectionProvider faceDetectionProvider) {
        return Map.of();
    }
}
