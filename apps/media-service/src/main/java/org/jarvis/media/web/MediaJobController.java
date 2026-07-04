package org.jarvis.media.web;

import jakarta.servlet.http.HttpServletRequest;
import org.jarvis.media.job.MediaJob;
import org.jarvis.media.job.MediaJobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Job lifecycle endpoints: list, read, and cancel. All scoped to the calling user.
 */
@RestController
@RequestMapping("/api/v1/media/jobs")
public class MediaJobController {

    private final MediaJobService jobService;
    private final MediaFeatureGate gate;

    public MediaJobController(MediaJobService jobService, MediaFeatureGate gate) {
        this.jobService = jobService;
        this.gate = gate;
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
}
