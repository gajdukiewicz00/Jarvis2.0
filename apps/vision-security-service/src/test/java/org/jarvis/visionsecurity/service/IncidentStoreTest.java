package org.jarvis.visionsecurity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.DecisionType;
import org.jarvis.visionsecurity.model.EmailDelivery;
import org.jarvis.visionsecurity.model.IncidentRecord;
import org.jarvis.visionsecurity.model.ScreenContextEvidence;
import org.jarvis.visionsecurity.model.StagePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void savesLoadsListsAndPrunesIncidentsPerUser() throws Exception {
        VisionSecurityProperties properties = new VisionSecurityProperties();
        properties.getStorage().setRoot(tempDir.toString());
        properties.getStorage().setMaxIncidentsPerUser(2);

        IncidentStore store = new IncidentStore(properties, new ObjectMapper().findAndRegisterModules());
        String userId = "owner@example.com";

        IncidentRecord first = incident(store, userId, Instant.parse("2026-04-07T10:00:00Z"));
        IncidentRecord second = incident(store, userId, Instant.parse("2026-04-07T11:00:00Z"));
        IncidentRecord third = incident(store, userId, Instant.parse("2026-04-07T12:00:00Z"));

        store.saveIncident(first);
        store.saveIncident(second);
        store.saveIncident(third);

        List<IncidentRecord> incidents = store.listIncidents(userId, 10);

        assertThat(incidents).hasSize(2);
        assertThat(incidents).extracting(IncidentRecord::incidentId)
                .containsExactly(third.incidentId(), second.incidentId());
        assertThat(store.loadIncident(userId, third.incidentId())).isEqualTo(third);
        assertThat(store.loadIncident(userId, first.incidentId())).isNull();
        assertThat(store.incidentCount(userId)).isEqualTo(2);
        assertThat(store.incidentsRoot(userId)).hasToString(
                tempDir.resolve("users").resolve("owner_example.com").resolve("incidents").toString()
        );
    }

    private IncidentRecord incident(IncidentStore store, String userId, Instant createdAt) throws Exception {
        Path directory = store.createIncidentDirectory(userId, createdAt);
        String incidentId = directory.getFileName().toString();
        return new IncidentRecord(
                incidentId,
                userId,
                createdAt,
                DecisionType.UNKNOWN_PERSON,
                1,
                "Detected faces stayed outside the owner threshold",
                List.of("GENERAL_DESKTOP"),
                new ScreenContextEvidence("Terminal", "gnome-terminal", "sudo cat /etc/hosts", List.of("DEVELOPMENT")),
                new StagePaths(
                        directory.resolve("original.png").toString(),
                        directory.resolve("enhanced.png").toString(),
                        directory.resolve("segmentation-mask.png").toString(),
                        directory.resolve("cleaned-mask.png").toString(),
                        directory.resolve("detection-result.png").toString(),
                        directory.resolve("final-decision.png").toString()
                ),
                directory.toString(),
                directory.resolve("webcam.png").toString(),
                directory.resolve("screenshot.png").toString(),
                directory.resolve("screen-ocr.txt").toString(),
                new EmailDelivery(true, true, "sent")
        );
    }
}
