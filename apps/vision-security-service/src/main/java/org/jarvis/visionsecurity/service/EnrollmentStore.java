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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class EnrollmentStore {

    private final VisionSecurityProperties properties;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, CachedSamples> sampleCache = new ConcurrentHashMap<>();

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

        long sourceVersion = computeVersion(samplePaths);
        CachedSamples cached = sampleCache.get(userId);
        if (cached != null && cached.version() == sourceVersion) {
            return cloneCachedSamples(cached.samples());
        }

        List<Mat> samples = new ArrayList<>();
        for (Path samplePath : samplePaths) {
            Mat mat = Imgcodecs.imread(samplePath.toString(), Imgcodecs.IMREAD_GRAYSCALE);
            if (!mat.empty()) {
                samples.add(mat);
            }
        }

        if (cached != null) {
            cached.samples().forEach(Mat::release);
        }
        sampleCache.put(userId, new CachedSamples(sourceVersion, cloneCachedSamples(samples)));
        return samples;
    }

    private long computeVersion(List<Path> samplePaths) throws IOException {
        long version = 1469598103934665603L;
        for (Path path : samplePaths) {
            version = 31L * version + path.getFileName().toString().hashCode();
            version = 31L * version + Files.getLastModifiedTime(path).toMillis();
            version = 31L * version + Files.size(path);
        }
        return version;
    }

    private List<Mat> cloneCachedSamples(List<Mat> source) {
        List<Mat> copies = new ArrayList<>(source.size());
        for (Mat mat : source) {
            copies.add(mat.clone());
        }
        return copies;
    }

    private record CachedSamples(long version, List<Mat> samples) {
    }

    public EnrollmentProfile saveEnrollment(
            String userId,
            List<Mat> normalizedSamples,
            double ownerThreshold,
            double uncertainThreshold
    ) throws IOException {
        Path directory = samplesDirectory(userId);
        recreateDirectory(directory);
        invalidateCache(userId);

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
        invalidateCache(userId);
    }

    private void invalidateCache(String userId) {
        CachedSamples previous = sampleCache.remove(userId);
        if (previous != null) {
            previous.samples().forEach(Mat::release);
        }
    }

    public Path userDirectory(String userId) {
        Path usersRoot = Path.of(properties.getStorage().getRoot(), "users").normalize();
        Path resolved = usersRoot.resolve(sanitize(userId)).normalize();
        if (resolved.equals(usersRoot) || !resolved.startsWith(usersRoot)) {
            throw new IllegalArgumentException("userId escapes the allowed storage directory");
        }
        return resolved;
    }

    public Path samplesDirectory(String userId) {
        return userDirectory(userId).resolve("enrollment").resolve("samples");
    }

    private Path profilePath(String userId) {
        return userDirectory(userId).resolve("enrollment").resolve("profile.json");
    }

    private String sanitize(String userId) {
        String cleaned = userId.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (cleaned.isBlank() || cleaned.equals(".") || cleaned.equals("..")) {
            throw new IllegalArgumentException("Invalid userId");
        }
        return cleaned;
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
