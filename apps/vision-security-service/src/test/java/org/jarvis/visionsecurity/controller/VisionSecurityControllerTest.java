package org.jarvis.visionsecurity.controller;

import org.jarvis.visionsecurity.model.CapabilityStatus;
import org.jarvis.visionsecurity.model.DecisionType;
import org.jarvis.visionsecurity.model.EmailDelivery;
import org.jarvis.visionsecurity.model.EnrollmentResult;
import org.jarvis.visionsecurity.model.GpuStatus;
import org.jarvis.visionsecurity.model.IncidentRecord;
import org.jarvis.visionsecurity.model.PipelineResult;
import org.jarvis.visionsecurity.model.PipelineSnapshotResult;
import org.jarvis.visionsecurity.model.ScreenContextEvidence;
import org.jarvis.visionsecurity.model.VisionSecurityConfigView;
import org.jarvis.visionsecurity.model.VisionSecurityStatus;
import org.jarvis.visionsecurity.service.VisionSecurityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice tests for {@link VisionSecurityController}, mirroring the
 * {@code @WebMvcTest} + {@code @MockBean} pattern used across the other
 * services (e.g. {@code AnalyticsControllerTest}), plus the
 * {@link VisionSecurityExceptionHandler} wiring for the error branches.
 */
@WebMvcTest(controllers = VisionSecurityController.class)
@AutoConfigureMockMvc(addFilters = false)
class VisionSecurityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VisionSecurityManager manager;

    private TestingAuthenticationToken authenticatedUser(String userId) {
        return new TestingAuthenticationToken(userId, "n/a", "ROLE_USER");
    }

    @Test
    void statusReturnsStatusForAuthenticatedUser() throws Exception {
        when(manager.statusFor("owner")).thenReturn(sampleStatus("owner"));

        mockMvc.perform(get("/api/v1/vision-security/status").principal(authenticatedUser("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeUserId").value("owner"))
                .andExpect(jsonPath("$.monitoringEnabled").value(true));
    }

    @Test
    void configReturnsConfigView() throws Exception {
        when(manager.configView()).thenReturn(new VisionSecurityConfigView(
                2000L, 3, 60L, "/data", "owner@example.com", "eng", false, "x11"));

        mockMvc.perform(get("/api/v1/vision-security/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ocrLanguage").value("eng"))
                .andExpect(jsonPath("$.displayServer").value("x11"));
    }

    @Test
    void startMonitoringDelegatesToManager() throws Exception {
        when(manager.startMonitoring("owner")).thenReturn(sampleStatus("owner"));

        mockMvc.perform(post("/api/v1/vision-security/monitoring/start").principal(authenticatedUser("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monitoringEnabled").value(true));
    }

    @Test
    void stopMonitoringDelegatesToManager() throws Exception {
        VisionSecurityStatus stopped = new VisionSecurityStatus(
                "DEGRADED", false, "owner", false, null, null, "Monitoring stopped",
                0, 0, null, 0,
                new CapabilityStatus("AVAILABLE", "ok"),
                new CapabilityStatus("AVAILABLE", "ok"),
                new CapabilityStatus("AVAILABLE", "ok"),
                new CapabilityStatus("AVAILABLE", "ok"),
                new GpuStatus(false, false, "cpu", "cpu"),
                new VisionSecurityConfigView(2000L, 3, 60L, "/data", "", "eng", false, "x11"));
        when(manager.stopMonitoring("owner")).thenReturn(stopped);

        mockMvc.perform(post("/api/v1/vision-security/monitoring/stop").principal(authenticatedUser("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monitoringEnabled").value(false));
    }

    @Test
    void captureEnrollmentWithNullBodyUsesDefaultSampleCount() throws Exception {
        when(manager.captureEnrollment("owner", null)).thenReturn(enrollmentResult());

        mockMvc.perform(post("/api/v1/vision-security/enrollment/capture").principal(authenticatedUser("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("owner"));
    }

    @Test
    void captureEnrollmentWithExplicitSampleCount() throws Exception {
        when(manager.captureEnrollment("owner", 5)).thenReturn(enrollmentResult());

        mockMvc.perform(post("/api/v1/vision-security/enrollment/capture")
                        .principal(authenticatedUser("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sampleCount\":5}"))
                .andExpect(status().isOk());
    }

    @Test
    void captureEnrollmentConflictMapsToVisionSecurityConflict() throws Exception {
        when(manager.captureEnrollment(eq("owner"), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("Stop monitoring before starting owner enrollment"));

        mockMvc.perform(post("/api/v1/vision-security/enrollment/capture").principal(authenticatedUser("owner")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("VISION_SECURITY_CONFLICT"))
                .andExpect(jsonPath("$.message").value("Stop monitoring before starting owner enrollment"));
    }

    @Test
    void importEnrollmentDelegatesWithDatasetPath() throws Exception {
        when(manager.importEnrollmentFromDataset(eq("owner"), eq(java.nio.file.Path.of("/data/owner-photos"))))
                .thenReturn(enrollmentResult());

        mockMvc.perform(post("/api/v1/vision-security/enrollment/import")
                        .principal(authenticatedUser("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetDirectory\":\"/data/owner-photos\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleCount").value(5));
    }

    @Test
    void importEnrollmentBadRequestMapsToValidationError() throws Exception {
        when(manager.importEnrollmentFromDataset(eq("owner"), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalArgumentException("No image files found in /data/missing"));

        mockMvc.perform(post("/api/v1/vision-security/enrollment/import")
                        .principal(authenticatedUser("owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetDirectory\":\"/data/missing\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void resetEnrollmentDelegatesToManager() throws Exception {
        when(manager.resetEnrollment("owner")).thenReturn(sampleStatus("owner"));

        mockMvc.perform(post("/api/v1/vision-security/enrollment/reset").principal(authenticatedUser("owner")))
                .andExpect(status().isOk());
    }

    @Test
    void incidentsListsWithDefaultLimit() throws Exception {
        when(manager.listIncidents("owner", 20)).thenReturn(List.of(incidentRecord()));

        mockMvc.perform(get("/api/v1/vision-security/incidents").principal(authenticatedUser("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].incidentId").value("20260501T000000Z-abc"));
    }

    @Test
    void incidentsHonorsCustomLimit() throws Exception {
        when(manager.listIncidents("owner", 5)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/vision-security/incidents")
                        .principal(authenticatedUser("owner"))
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void incidentReturnsSingleRecord() throws Exception {
        IncidentRecord record = incidentRecord();
        when(manager.incident("owner", record.incidentId())).thenReturn(record);

        mockMvc.perform(get("/api/v1/vision-security/incidents/{id}", record.incidentId())
                        .principal(authenticatedUser("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("owner"));
    }

    @Test
    void incidentNotFoundMapsToBadRequest() throws Exception {
        when(manager.incident("owner", "missing")).thenThrow(new IllegalArgumentException("Incident not found: missing"));

        mockMvc.perform(get("/api/v1/vision-security/incidents/{id}", "missing").principal(authenticatedUser("owner")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Incident not found: missing"));
    }

    @Test
    void capturePipelineDelegatesToManager() throws Exception {
        PipelineResult pipelineResult = new PipelineResult(DecisionType.OWNER_PRESENT, 1, "Owner recognised",
                List.of(), null, null);
        when(manager.capturePipelineSnapshot("owner")).thenReturn(
                new PipelineSnapshotResult("owner", Instant.parse("2026-05-01T00:00:00Z"), "/tmp/snap", pipelineResult));

        mockMvc.perform(post("/api/v1/vision-security/pipeline/capture").principal(authenticatedUser("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outputDirectory").value("/tmp/snap"));
    }

    @Test
    void sendTestAlertReturnsDeliverySummary() throws Exception {
        when(manager.sendTestAlert("owner")).thenReturn(new EmailDelivery(true, true, "Test alert sent"));

        mockMvc.perform(post("/api/v1/vision-security/alerts/test").principal(authenticatedUser("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempted").value(true))
                .andExpect(jsonPath("$.sent").value(true))
                .andExpect(jsonPath("$.message").value("Test alert sent"));
    }

    private VisionSecurityStatus sampleStatus(String userId) {
        return new VisionSecurityStatus(
                "READY", true, userId, true, DecisionType.OWNER_PRESENT, Instant.now(), "Owner recognised",
                1, 0, null, 0,
                new CapabilityStatus("AVAILABLE", "ok"),
                new CapabilityStatus("AVAILABLE", "ok"),
                new CapabilityStatus("AVAILABLE", "ok"),
                new CapabilityStatus("AVAILABLE", "ok"),
                new GpuStatus(false, false, "cpu", "cpu"),
                new VisionSecurityConfigView(2000L, 3, 60L, "/data", "", "eng", false, "x11"));
    }

    private EnrollmentResult enrollmentResult() {
        return new EnrollmentResult("owner", Instant.now(), 5, 70.0, 100.0, "/data/owner");
    }

    private IncidentRecord incidentRecord() {
        return new IncidentRecord(
                "20260501T000000Z-abc",
                "owner",
                Instant.parse("2026-05-01T00:00:00Z"),
                DecisionType.UNKNOWN_PERSON,
                1,
                "Detected faces stayed outside the owner threshold",
                List.of("GENERAL_DESKTOP"),
                new ScreenContextEvidence("", "", "", List.of("GENERAL_DESKTOP")),
                null,
                "/tmp/incident",
                null,
                null,
                null,
                new EmailDelivery(true, true, "sent")
        );
    }
}
