package org.jarvis.media.ffmpeg;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.media.job.JobArtifact;
import org.jarvis.media.job.JobOutcome;
import org.jarvis.media.job.JobType;
import org.jarvis.media.job.MediaJob;
import org.jarvis.media.job.MediaJobService;
import org.jarvis.media.probe.ProbeRequest;
import org.jarvis.media.probe.ProbeResult;
import org.jarvis.media.probe.ProbeService;
import org.jarvis.media.workspace.WorkspaceManager;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Extracts the selected audio stream to a lossless file as an async job. Input-path
 * validation, format parsing, and stream selection happen synchronously so bad
 * requests fail fast with 4xx; only the ffmpeg work itself runs on the executor.
 */
@Slf4j
@Service
public class AudioExtractionService {

    private final MediaJobService jobService;
    private final ProbeService probeService;
    private final FFmpegClient ffmpeg;
    private final WorkspaceManager workspace;

    public AudioExtractionService(MediaJobService jobService, ProbeService probeService,
                                  FFmpegClient ffmpeg, WorkspaceManager workspace) {
        this.jobService = jobService;
        this.probeService = probeService;
        this.ffmpeg = ffmpeg;
        this.workspace = workspace;
    }

    public MediaJob submit(String userId, ExtractAudioRequest request) {
        Path input = workspace.validateInputPath(request.inputFile());
        AudioFormat format = AudioFormat.fromText(request.format());
        int streamIndex = resolveStreamIndex(request);

        String workId = workspace.newWorkId();
        Path output = workspace.resolveInWorkspace(workId + "/audio." + format.extension());

        return jobService.submit(JobType.EXTRACT_AUDIO, userId, request.inputFile(), token -> {
            token.throwIfCancelled();
            ffmpeg.extractAudio(input, streamIndex, output, format);
            JobArtifact artifact = JobArtifact.of("audio", output.toString(), format.contentType(),
                    workspace.sizeOrZero(output));
            return JobOutcome.of(
                    List.of(artifact),
                    Map.of("audioStreamIndex", streamIndex,
                            "format", format.extension(),
                            "sourcePreserved", true));
        });
    }

    private int resolveStreamIndex(ExtractAudioRequest request) {
        if (request.audioStreamIndex() != null) {
            if (request.audioStreamIndex() < 0) {
                throw new IllegalArgumentException("audioStreamIndex must be >= 0");
            }
            return request.audioStreamIndex();
        }
        ProbeResult probe = probeService.probe(
                new ProbeRequest(request.inputFile(), request.preferredLanguage(), null));
        if (probe.selectedAudioIndex() == null) {
            throw new IllegalArgumentException("No audio stream found to extract");
        }
        return probe.selectedAudioIndex();
    }
}
