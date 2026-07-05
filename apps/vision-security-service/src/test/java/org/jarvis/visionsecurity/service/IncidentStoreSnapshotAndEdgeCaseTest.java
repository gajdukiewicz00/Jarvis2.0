package org.jarvis.visionsecurity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link IncidentStore} branches not exercised by {@link IncidentStoreTest}:
 * the snapshot-directory pair, the "no incidents yet" empty-root branches, and the
 * corrupted-incident-file tolerance in {@code listIncidents}.
 */
class IncidentStoreSnapshotAndEdgeCaseTest {

    @Test
    void createSnapshotDirectoryCreatesDirectoryUnderSnapshotsRoot(@TempDir Path tempDir) throws Exception {
        VisionSecurityProperties properties = new VisionSecurityProperties();
        properties.getStorage().setRoot(tempDir.toString());
        IncidentStore store = new IncidentStore(properties, new ObjectMapper().findAndRegisterModules());
        String userId = "owner@example.com";

        Path snapshotDir = store.createSnapshotDirectory(userId, Instant.parse("2026-04-07T10:00:00Z"));

        assertThat(snapshotDir).isDirectory();
        assertThat(snapshotDir.getParent()).isEqualTo(store.snapshotsRoot(userId));
        assertThat(store.snapshotsRoot(userId)).hasToString(
                tempDir.resolve("users").resolve("owner_example.com").resolve("snapshots").toString());
    }

    @Test
    void listIncidentsReturnsEmptyListWhenUserHasNoIncidentsYet(@TempDir Path tempDir) throws Exception {
        VisionSecurityProperties properties = new VisionSecurityProperties();
        properties.getStorage().setRoot(tempDir.toString());
        IncidentStore store = new IncidentStore(properties, new ObjectMapper().findAndRegisterModules());

        assertThat(store.listIncidents("brand-new-user", 10)).isEmpty();
    }

    @Test
    void incidentCountIsZeroWhenUserHasNoIncidentsYet(@TempDir Path tempDir) throws Exception {
        VisionSecurityProperties properties = new VisionSecurityProperties();
        properties.getStorage().setRoot(tempDir.toString());
        IncidentStore store = new IncidentStore(properties, new ObjectMapper().findAndRegisterModules());

        assertThat(store.incidentCount("brand-new-user")).isZero();
    }

    @Test
    void listIncidentsSkipsDirectoriesWithUnparsableIncidentJson(@TempDir Path tempDir) throws Exception {
        VisionSecurityProperties properties = new VisionSecurityProperties();
        properties.getStorage().setRoot(tempDir.toString());
        IncidentStore store = new IncidentStore(properties, new ObjectMapper().findAndRegisterModules());
        String userId = "owner@example.com";

        Path corruptDir = store.incidentsRoot(userId).resolve("20260101T000000Z-broken");
        Files.createDirectories(corruptDir);
        Files.writeString(corruptDir.resolve("incident.json"), "{not valid json", StandardCharsets.UTF_8);

        List<org.jarvis.visionsecurity.model.IncidentRecord> incidents = store.listIncidents(userId, 10);

        assertThat(incidents).isEmpty();
    }
}
