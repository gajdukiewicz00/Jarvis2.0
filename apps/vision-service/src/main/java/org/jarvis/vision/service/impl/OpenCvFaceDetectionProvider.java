package org.jarvis.vision.service.impl;

import lombok.RequiredArgsConstructor;
import org.jarvis.common.vision.VisionFaceRegion;
import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.FaceDetectionProvider;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class OpenCvFaceDetectionProvider implements FaceDetectionProvider {

    private static final String PROVIDER = "opencv-haarcascade";

    private final VisionServiceProperties properties;
    private final OpenCvRuntime openCvRuntime;

    @Override
    public String providerId() {
        return PROVIDER;
    }

    @Override
    public String cacheKey() {
        Path configured = properties.getFaceCascadePath();
        if (configured != null && !configured.toString().isBlank()) {
            return PROVIDER + "::cascade=" + configured.toAbsolutePath();
        }
        return PROVIDER + "::cascade=bundled-haarcascade";
    }

    @Override
    public DetectionResult detectFaces(BufferedImage image) {
        if (!openCvRuntime.isAvailable()) {
            return new DetectionResult(false, PROVIDER,
                    "OpenCV runtime unavailable: " + openCvRuntime.failureMessage(), List.of());
        }

        Optional<Path> cascadePath = resolveCascadePath();
        if (cascadePath.isEmpty()) {
            return new DetectionResult(false, PROVIDER,
                    "No Haar cascade configured or bundled for vision-service", List.of());
        }

        try {
            CascadeClassifier classifier = new CascadeClassifier(cascadePath.get().toString());
            if (classifier.empty()) {
                return new DetectionResult(false, PROVIDER,
                        "Failed to load Haar cascade from " + cascadePath.get(), List.of());
            }

            Mat input = OpenCvImageUtils.toMat(image);
            Mat grayscale = new Mat();
            Imgproc.cvtColor(input, grayscale, Imgproc.COLOR_BGR2GRAY);
            Imgproc.equalizeHist(grayscale, grayscale);

            MatOfRect detections = new MatOfRect();
            int minimumFaceSize = properties.getMinimumFaceSizePixels();
            classifier.detectMultiScale(
                    grayscale,
                    detections,
                    1.1d,
                    4,
                    0,
                    new Size(minimumFaceSize, minimumFaceSize),
                    new Size());

            Rect[] rects = detections.toArray();
            List<VisionFaceRegion> faces = new ArrayList<>(rects.length);
            for (Rect rect : rects) {
                faces.add(new VisionFaceRegion(rect.x, rect.y, rect.width, rect.height));
            }
            faces.sort(Comparator.comparingInt(region -> -region.width() * region.height()));

            return new DetectionResult(
                    true,
                    PROVIDER,
                    faces.isEmpty() ? "No face detected" : "Detected " + faces.size() + " face(s)",
                    faces);
        } catch (Exception exception) {
            return new DetectionResult(false, PROVIDER, exception.getMessage(), List.of());
        }
    }

    private Optional<Path> resolveCascadePath() {
        Path configured = properties.getFaceCascadePath();
        if (configured != null && Files.isRegularFile(configured)) {
            return Optional.of(configured);
        }
        ClassPathResource resource = new ClassPathResource("cv/haarcascade_frontalface_default.xml");
        if (resource.exists()) {
            try (InputStream input = resource.getInputStream()) {
                Path temp = Files.createTempFile("jarvis-vision-haarcascade-", ".xml");
                Files.copy(input, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                temp.toFile().deleteOnExit();
                return Optional.of(temp);
            } catch (Exception ignored) {
            }
        }
        List<Path> candidates = List.of(
                Path.of("/usr/share/opencv4/haarcascades/haarcascade_frontalface_default.xml"),
                Path.of("/usr/share/opencv/haarcascades/haarcascade_frontalface_default.xml"));
        return candidates.stream().filter(Files::isRegularFile).findFirst();
    }
}
