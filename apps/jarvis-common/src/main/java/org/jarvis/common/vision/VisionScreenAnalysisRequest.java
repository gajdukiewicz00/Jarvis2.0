package org.jarvis.common.vision;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record VisionScreenAnalysisRequest(
        @NotNull
        @Size(min = 1, max = 10_485_760)
        byte[] imageBytes,
        @Pattern(
                regexp = "^(jpg|jpeg|png|bmp)$",
                flags = Pattern.Flag.CASE_INSENSITIVE,
                message = "imageFormat must be one of jpg, jpeg, png, bmp")
        String imageFormat,
        @Size(max = 120)
        String source,
        @Size(max = 120)
        String requestId,
        Map<String, String> metadata) {

    public VisionScreenAnalysisRequest {
        imageBytes = imageBytes == null ? new byte[0] : imageBytes.clone();
        imageFormat = imageFormat == null || imageFormat.isBlank() ? "png" : imageFormat.trim().toLowerCase();
        source = source == null || source.isBlank() ? "unknown" : source.trim();
        requestId = requestId == null ? "" : requestId.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
