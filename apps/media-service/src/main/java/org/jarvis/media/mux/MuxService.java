package org.jarvis.media.mux;

import org.jarvis.media.ffmpeg.FFmpegClient;
import org.jarvis.media.job.JobArtifact;
import org.jarvis.media.job.JobOutcome;
import org.jarvis.media.job.JobType;
import org.jarvis.media.job.MediaJob;
import org.jarvis.media.job.MediaJobService;
import org.jarvis.media.workspace.WorkspaceManager;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Produces a NEW container that contains every original stream plus the added Russian
 * subtitle and/or audio track. The original file is an input only — ffmpeg writes a
 * separate output and copies (never re-encodes) the source streams, so nothing is lost
 * and the original is left untouched.
 */
@Service
public class MuxService {

    private final MediaJobService jobService;
    private final FFmpegClient ffmpeg;
    private final WorkspaceManager workspace;

    public MuxService(MediaJobService jobService, FFmpegClient ffmpeg, WorkspaceManager workspace) {
        this.jobService = jobService;
        this.ffmpeg = ffmpeg;
        this.workspace = workspace;
    }

    public MediaJob submit(String userId, MuxRequest request) {
        Path original = workspace.validateInputPath(request.originalFile());
        Path subtitle = request.subtitleFile() == null ? null
                : workspace.validateInputPath(request.subtitleFile());
        Path dubAudio = request.dubAudioFile() == null ? null
                : workspace.validateInputPath(request.dubAudioFile());
        if (subtitle == null && dubAudio == null) {
            throw new IllegalArgumentException("At least one of subtitleFile or dubAudioFile must be provided");
        }

        String workId = workspace.newWorkId();
        String outputName = (request.outputName() == null || request.outputName().isBlank())
                ? "output.mkv" : request.outputName();
        Path output = workspace.resolveInWorkspace(workId + "/" + outputName);

        return jobService.submit(JobType.MUX, userId, request.originalFile(), token -> {
            token.throwIfCancelled();
            ffmpeg.mux(original, subtitle, dubAudio, output);
            JobArtifact artifact = JobArtifact.of("video", output.toString(),
                    "video/x-matroska", workspace.sizeOrZero(output));
            return JobOutcome.of(List.of(artifact), Map.of(
                    "originalPreserved", true,
                    "addedSubtitle", subtitle != null,
                    "addedAudio", dubAudio != null));
        });
    }
}
