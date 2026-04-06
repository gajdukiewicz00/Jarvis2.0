package org.jarvis.vision.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.FaceAlignmentService;
import org.jarvis.vision.service.FaceDetectionProvider;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class OwnerReferenceFaceLoader {

    private final VisionServiceProperties properties;

    public List<ReferenceFace> loadReferenceFaces(FaceDetectionProvider faceDetectionProvider) {
        List<Path> referenceFiles = listReferenceFiles();
        List<ReferenceFace> faces = new ArrayList<>();
        referenceFiles.forEach(path -> loadReferenceFace(path, faceDetectionProvider).ifPresent(faces::add));
        return List.copyOf(faces);
    }

    public List<ReferenceFace> loadReferenceFaces(FaceDetectionProvider faceDetectionProvider,
                                                  FaceAlignmentService faceAlignmentService) {
        List<Path> referenceFiles = listReferenceFiles();
        List<ReferenceFace> faces = new ArrayList<>();
        referenceFiles.forEach(path -> loadReferenceFace(path, faceDetectionProvider, faceAlignmentService)
                .ifPresent(faces::add));
        return List.copyOf(faces);
    }

    public List<Path> listReferenceFiles() {
        Path directory = properties.getOwnerReferenceDirectory();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        try (java.util.stream.Stream<Path> stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(this::isSupportedReference)
                    .sorted()
                    .toList();
        } catch (Exception exception) {
            log.warn("Failed to read owner reference directory {}: {}", directory, exception.getMessage());
            return List.of();
        }
    }

    private java.util.Optional<ReferenceFace> loadReferenceFace(Path path, FaceDetectionProvider faceDetectionProvider) {
        try {
            BufferedImage reference = ImageIO.read(path.toFile());
            if (reference == null) {
                return java.util.Optional.empty();
            }

            FaceDetectionProvider.DetectionResult detectionResult = faceDetectionProvider.detectFaces(reference);
            BufferedImage preparedFace = detectionResult.faces().isEmpty()
                    ? reference
                    : cropPrimaryFace(reference, detectionResult);
            return java.util.Optional.of(new ReferenceFace(path, preparedFace));
        } catch (Exception exception) {
            log.debug("Skipping unreadable owner reference {}: {}", path, exception.getMessage());
            return java.util.Optional.empty();
        }
    }

    private java.util.Optional<ReferenceFace> loadReferenceFace(Path path,
                                                                FaceDetectionProvider faceDetectionProvider,
                                                                FaceAlignmentService faceAlignmentService) {
        return loadReferenceFace(path, faceDetectionProvider)
                .map(referenceFace -> {
                    if (faceAlignmentService == null) {
                        return referenceFace;
                    }
                    return new ReferenceFace(
                            referenceFace.sourcePath(),
                            faceAlignmentService.align(referenceFace.faceImage()).faceImage());
                });
    }

    private static BufferedImage cropPrimaryFace(BufferedImage image, FaceDetectionProvider.DetectionResult detectionResult) {
        var face = detectionResult.faces().getFirst();
        return OpenCvImageUtils.crop(image, face.x(), face.y(), face.width(), face.height());
    }

    private boolean isSupportedReference(Path path) {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return properties.getReferenceExtensions().stream()
                .anyMatch(extension -> filename.endsWith("." + extension));
    }

    public record ReferenceFace(Path sourcePath, BufferedImage faceImage) {
    }
}
