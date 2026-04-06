package org.jarvis.common.vision;

public record VisionOwnerReferenceEnrollResponse(
        boolean stored,
        String label,
        String message,
        int referenceImageCount,
        String storedFilename) {

    public VisionOwnerReferenceEnrollResponse {
        label = label == null ? "" : label;
        message = message == null ? "" : message;
        storedFilename = storedFilename == null ? "" : storedFilename;
    }
}
