package org.jarvis.visionsecurity.model;

import java.util.List;

public record ScreenContextEvidence(
        String activeWindowTitle,
        String activeProcessName,
        String ocrText,
        List<String> semanticTags
) {
}
