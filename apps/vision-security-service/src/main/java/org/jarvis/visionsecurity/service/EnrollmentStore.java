package org.jarvis.visionsecurity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.EnrollmentProfile;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class EnrollmentStore {

    private final VisionSecurityProperties properties;
    private final ObjectMapper objectMapper;

    public boolean isEnrolled(String userId) {
        return Files.isRegularFile(profilePath(userId));
    }

    public EnrollmentProfile loadProfile(String userId) throws IOException {
        Path path = profilePath(userId);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        return objectMapper.readValue(path.toFile(), EnrollmentProfile.class);
    }

    public List<Mat> loadSamples(String userId) throws IOException {
        Path directory = samplesDirectory(userId);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        List<Path> samplePaths;
        try (Stream<Path> stream = Files.list(directory)) {
            samplePaths = stream
                    .filter(path -> path.getFileName().toString().endsWith(".png"))
                    .sorted()
                    .toList();
        }

        List<Mat> samples = new ArrayList<>();
        for (Path samplePath : samplePaths) {
            Mat mat = Imgcodecs.imread(samplePath.toString(), Imgcodecs.IMREAD_GRAYSCALE);
            if (!mat.empty()) {
                samples.add(mat);
            }
        }
        return samples;
    }

    public EnrollmentProfile saveEnrollment(
            String userId,
            List<Mat> normalizedSamples,
            double ownerThreshold,
            double uncertainThreshold
    ) throws IOException {
        Path directory = samplesDirectory(userId);
        recreateDirectory(directory);

        int index = 1;
        for (Mat sample : normalizedSamples) {
            String filename = String.format(Locale.ROOT, "sample-%02d.png", index++);
            Imgcodecs.imwrite(directory.resolve(filename).toString(), sample);
        }

        EnrollmentProfile profile = new EnrollmentProfile(
                userId,
                Instant.now(),
                normalizedSamples.size(),
                ownerThreshold,
                uncertainThreshold,
                directory.toString()
        );
        Files.createDirectories(profilePath(userId).getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(profilePath(userId).toFile(), profile);
        return profile;
    }

    public void reset(String userId) throws IOException {
        deleteRecursively(userDirectory(userId));
    }

    public Path userDirectory(String userId) {
        return Path.of(properties.getStorage().getRoot(), "users", sanitize(userId));
    }

    public Path samplesDirectory(String userId) {
        return userDirectory(userId).resolve("enrollment").resolve("samples");
    }

    private Path profilePath(String userId) {
        return userDirectory(userId).resolve("enrollment").resolve("profile.json");
    }

    private String sanitize(String userId) {
        return userId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void recreateDirectory(Path directory) throws IOException {
        deleteRecursively(directory);
        Files.createDirectories(directory);
    }

    private void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(current -> {
                try {
                    Files.deleteIfExists(current);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw ex;
        }
    }
}
