package org.jarvis.vision.service.impl;

import lombok.RequiredArgsConstructor;
import org.jarvis.common.vision.VisionOwnerReferenceEnrollRequest;
import org.jarvis.common.vision.VisionOwnerReferenceEnrollResponse;
import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.OwnerReferenceService;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class FileSystemOwnerReferenceService implements OwnerReferenceService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC);

    private final VisionServiceProperties properties;
    private final OwnerReferenceEmbeddingCache ownerReferenceEmbeddingCache;

    @Override
    public int countReferences() {
        Path directory = properties.getOwnerReferenceDirectory();
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(directory)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(this::isSupportedReference)
                    .count();
        } catch (Exception exception) {
            return 0;
        }
    }

    @Override
    public VisionOwnerReferenceEnrollResponse enroll(VisionOwnerReferenceEnrollRequest request) throws Exception {
        if (!properties.getEnrollment().isEnabled()) {
            return new VisionOwnerReferenceEnrollResponse(
                    false,
                    request.label(),
                    "Owner reference enrollment is disabled",
                    countReferences(),
                    "");
        }
        if (request.imageBytes().length == 0) {
            return new VisionOwnerReferenceEnrollResponse(
                    false,
                    request.label(),
                    "Image payload is required",
                    countReferences(),
                    "");
        }

        Path directory = Files.createDirectories(properties.getOwnerReferenceDirectory());
        String safeLabel = request.label().isBlank()
                ? "owner"
                : request.label().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        String extension = request.imageFormat().replaceAll("[^a-z0-9]", "");
        String filename = FORMATTER.format(Instant.now()) + "-" + safeLabel + "." + extension;
        Path target = directory.resolve(filename);
        Files.write(target, request.imageBytes());
        ownerReferenceEmbeddingCache.invalidateAll("owner-reference-enrollment");

        return new VisionOwnerReferenceEnrollResponse(
                true,
                request.label(),
                "Owner reference stored",
                countReferences(),
                filename);
    }

    private boolean isSupportedReference(Path path) {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return properties.getReferenceExtensions().stream()
                .anyMatch(extension -> filename.endsWith("." + extension));
    }
}
