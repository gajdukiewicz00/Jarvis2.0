package org.jarvis.visionsecurity.model;

public record CvBlock(
        String text,
        double confidence,
        RectBox bbox
) {
}
