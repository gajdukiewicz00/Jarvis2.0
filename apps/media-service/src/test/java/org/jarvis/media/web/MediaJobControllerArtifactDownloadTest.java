package org.jarvis.media.web;

import org.jarvis.media.config.MediaProperties;
import org.jarvis.media.job.ArtifactNotFoundException;
import org.jarvis.media.job.JobArtifact;
import org.jarvis.media.job.JobNotFoundException;
import org.jarvis.media.job.JobOutcome;
import org.jarvis.media.job.JobType;
import org.jarvis.media.job.MediaJob;
import org.jarvis.media.job.MediaJobService;
import org.jarvis.media.job.MediaJobStore;
import org.jarvis.media.support.MediaTestFactory;
import org.jarvis.media.workspace.PathValidationException;
import org.jarvis.media.workspace.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct (non-MockMvc) coverage of the artifact download endpoint: happy path,
 * ownership scoping, out-of-range index, a missing-on-disk artifact, and the
 * path-traversal guard for a tampered/out-of-workspace stored artifact path. See
 * {@code MediaServiceIntegrationTest} for the end-to-end HTTP version.
 */
class MediaJobControllerArtifactDownloadTest {

    @TempDir
    Path tmp;

    private MediaJobController controller;
    private MediaJobService jobService;
    private WorkspaceManager workspace;

    @BeforeEach
    void setUp() {
        workspace = MediaTestFactory.workspace(tmp);
        MediaJobStore store = MediaTestFactory.store();
        jobService = MediaTestFactory.syncJobService(store);
        MediaProperties props = MediaTestFactory.props(tmp);
        MediaFeatureGate gate = new MediaFeatureGate(props);
        controller = new MediaJobController(jobService, gate, workspace);
    }

    private MockHttpServletRequest requestAs(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", userId);
        return request;
    }

    @Test
    void downloadsArtifactContentForOwner() throws IOException {
        Path artifactPath = workspace.resolveInWorkspace("job1/subs.srt");
        Files.writeString(artifactPath, "1\n00:00:00,000 --> 00:00:02,000\nHello\n");

        MediaJob created = jobService.submit(JobType.RUSSIAN_SUBTITLES, "u1", "transcript.json", token ->
                JobOutcome.of(
                        List.of(JobArtifact.of("subtitle-srt", artifactPath.toString(), "application/x-subrip",
                                Files.size(artifactPath))),
                        Map.of()));

        ResponseEntity<Resource> response = controller.downloadArtifact(created.id(), 0, requestAs("u1"));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentDisposition().getFilename()).isEqualTo("subs.srt");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContentAsByteArray()).isEqualTo(Files.readAllBytes(artifactPath));
    }

    @Test
    void outOfRangeIndexThrowsArtifactNotFound() {
        MediaJob created = jobService.submit(JobType.MUX, "u1", "v.mkv", token -> JobOutcome.of(List.of(), Map.of()));

        assertThatThrownBy(() -> controller.downloadArtifact(created.id(), 0, requestAs("u1")))
                .isInstanceOf(ArtifactNotFoundException.class);
    }

    @Test
    void jobNotOwnedByCallerIsRejected() {
        MediaJob created = jobService.submit(JobType.MUX, "owner", "v.mkv",
                token -> JobOutcome.of(List.of(), Map.of()));

        assertThatThrownBy(() -> controller.downloadArtifact(created.id(), 0, requestAs("intruder")))
                .isInstanceOf(JobNotFoundException.class);
    }

    @Test
    void tamperedArtifactPathOutsideWorkspaceIsRejectedNotServed() {
        MediaJob created = jobService.submit(JobType.MUX, "u1", "v.mkv", token -> JobOutcome.of(
                List.of(JobArtifact.of("video", "/etc/passwd", "application/octet-stream", 10)), Map.of()));

        assertThatThrownBy(() -> controller.downloadArtifact(created.id(), 0, requestAs("u1")))
                .isInstanceOf(PathValidationException.class);
    }

    @Test
    void missingFileOnDiskIsReportedAsArtifactNotFound() {
        Path recordedButNeverWritten = workspace.resolveInWorkspace("job2/gone.wav");
        MediaJob created = jobService.submit(JobType.EXTRACT_AUDIO, "u1", "a.mkv", token -> JobOutcome.of(
                List.of(JobArtifact.of("audio", recordedButNeverWritten.toString(), "audio/wav", 0)), Map.of()));

        assertThatThrownBy(() -> controller.downloadArtifact(created.id(), 0, requestAs("u1")))
                .isInstanceOf(ArtifactNotFoundException.class);
    }
}
