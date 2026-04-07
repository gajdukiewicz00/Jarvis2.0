package org.jarvis.visionsecurity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.IncidentRecord;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class IncidentStore {

    private static final DateTimeFormatter DIRECTORY_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final VisionSecurityProperties properties;
    private final ObjectMapper objectMapper;

    public Path createIncidentDirectory(String userId, Instant createdAt) throws IOException {
        String directoryName = DIRECTORY_FORMAT.format(createdAt) + "-" + UUID.randomUUID();
        Path path = incidentsRoot(userId).resolve(directoryName);
        Files.createDirectories(path);
        return path;
    }

    public Path createSnapshotDirectory(String userId, Instant createdAt) throws IOException {
        String directoryName = DIRECTORY_FORMAT.format(createdAt) + "-" + UUID.randomUUID();
        Path path = snapshotsRoot(userId).resolve(directoryName);
        Files.createDirectories(path);
        return path;
    }

    public void saveIncident(IncidentRecord incidentRecord) throws IOException {
        Path directory = Path.of(incidentRecord.incidentDirectory());
        Files.createDirectories(directory);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("incident.json").toFile(), incidentRecord);
        pruneOldIncidents(incidentRecord.userId());
    }

    public IncidentRecord loadIncident(String userId, String incidentId) throws IOException {
        Path directory = incidentsRoot(userId).resolve(incidentId);
        Path file = directory.resolve("incident.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return objectMapper.readValue(file.toFile(), IncidentRecord.class);
    }

    public List<IncidentRecord> listIncidents(String userId, int limit) throws IOException {
        Path root = incidentsRoot(userId);
        if (!Files.isDirectory(root)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(root)) {
            return stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.reverseOrder())
                    .limit(Math.max(1, limit))
                    .map(path -> path.resolve("incident.json"))
                    .filter(Files::isRegularFile)
                    .map(this::readIncidentUnchecked)
                    .filter(record -> record != null)
                    .toList();
        }
    }

    public int incidentCount(String userId) throws IOException {
        Path root = incidentsRoot(userId);
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(root)) {
            return (int) stream.filter(Files::isDirectory).count();
        }
    }

    private IncidentRecord readIncidentUnchecked(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), IncidentRecord.class);
        } catch (IOException ex) {
            return null;
        }
    }

    private void pruneOldIncidents(String userId) throws IOException {
        Path root = incidentsRoot(userId);
        if (!Files.isDirectory(root)) {
            return;
        }

        List<Path> directories;
        try (Stream<Path> stream = Files.list(root)) {
            directories = stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }

        int keep = Math.max(1, properties.getStorage().getMaxIncidentsPerUser());
        for (int index = keep; index < directories.size(); index++) {
            deleteRecursively(directories.get(index));
        }
    }

    public Path incidentsRoot(String userId) {
        return userRoot(userId).resolve("incidents");
    }

    public Path snapshotsRoot(String userId) {
        return userRoot(userId).resolve("snapshots");
    }

    private Path userRoot(String userId) {
        return Path.of(properties.getStorage().getRoot(), "users", sanitize(userId));
    }

    private String sanitize(String userId) {
        return userId.replaceAll("[^a-zA-Z0-9._-]", "_");
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
