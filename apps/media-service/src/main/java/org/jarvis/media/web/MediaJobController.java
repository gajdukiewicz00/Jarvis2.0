package org.jarvis.media.web;

import jakarta.servlet.http.HttpServletRequest;
import org.jarvis.media.job.ArtifactNotFoundException;
import org.jarvis.media.job.JobArtifact;
import org.jarvis.media.job.MediaJob;
import org.jarvis.media.job.MediaJobService;
import org.jarvis.media.workspace.WorkspaceManager;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Job lifecycle endpoints: list, read, cancel, and artifact download. All scoped to
 * the calling user.
 */
@RestController
@RequestMapping("/api/v1/media/jobs")
public class MediaJobController {

    private final MediaJobService jobService;
    private final MediaFeatureGate gate;
    private final WorkspaceManager workspace;

    public MediaJobController(MediaJobService jobService, MediaFeatureGate gate, WorkspaceManager workspace) {
        this.jobService = jobService;
        this.gate = gate;
        this.workspace = workspace;
    }

    @GetMapping
    public List<JobView> list(HttpServletRequest request) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(request);
        return jobService.listJobs(userId).stream().map(JobView::from).toList();
    }

    @GetMapping("/{id}")
    public JobView get(@PathVariable String id, HttpServletRequest request) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(request);
        return JobView.from(jobService.getJob(id, userId));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String id, HttpServletRequest request) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(request);
        boolean cancelled = jobService.cancel(id, userId);
        MediaJob job = jobService.getJob(id, userId);
        return ResponseEntity.ok(Map.of(
                "cancelled", cancelled,
                "status", job.status().name(),
                "jobId", id));
    }

    /**
     * Download one artifact produced by a job, addressed by its position in {@code
     * outputFiles} (stable within a job's lifetime — the list is only ever appended
     * to once, on completion). The client never supplies a raw file path: the
     * artifact's recorded path is re-validated against the workspace root via
     * {@link WorkspaceManager#validateArtifactPath(String)} before anything is
     * read from disk, so this endpoint cannot be used to read arbitrary files even
     * if a stored job record were somehow tampered with.
     */
    @GetMapping("/{id}/artifacts/{index}")
    public ResponseEntity<Resource> downloadArtifact(@PathVariable String id, @PathVariable int index,
                                                      HttpServletRequest request) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(request);
        MediaJob job = jobService.getJob(id, userId);
        JobArtifact artifact = artifactAt(job, index);

        Path resolved = workspace.validateArtifactPath(artifact.path());
        if (!Files.isRegularFile(resolved)) {
            throw new ArtifactNotFoundException(id, index);
        }

        Resource resource = new FileSystemResource(resolved);
        long size = workspace.sizeOrZero(resolved);
        return ResponseEntity.ok()
                .contentType(safeMediaType(artifact.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + sanitizeFilename(resolved.getFileName().toString()) + "\"")
                .contentLength(size)
                .body(resource);
    }

    private JobArtifact artifactAt(MediaJob job, int index) {
        List<JobArtifact> artifacts = job.outputFiles();
        if (index < 0 || index >= artifacts.size()) {
            throw new ArtifactNotFoundException(job.id(), index);
        }
        return artifacts.get(index);
    }

    private MediaType safeMediaType(String contentType) {
        try {
            return (contentType == null || contentType.isBlank())
                    ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /** Strip anything but a conservative filename charset before it lands in a response header. */
    private String sanitizeFilename(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
