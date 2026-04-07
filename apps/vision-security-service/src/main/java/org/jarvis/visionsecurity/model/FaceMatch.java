package org.jarvis.visionsecurity.model;

public record FaceMatch(
        RectBox box,
        FaceVerdict verdict,
        double confidence
) {
}
