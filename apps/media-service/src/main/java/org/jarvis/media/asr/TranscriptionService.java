package org.jarvis.media.asr;

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
 * Produces a timestamped transcript from an audio artifact as an async job, storing
 * the transcript as a JSON workspace artifact for downstream subtitle/dub steps.
 */
@Service
public class TranscriptionService {

    private final MediaJobService jobService;
    private final AsrProvider asr;
    private final TranscriptCodec codec;
    private final WorkspaceManager workspace;

    public TranscriptionService(MediaJobService jobService, AsrProvider asr,
                                TranscriptCodec codec, WorkspaceManager workspace) {
        this.jobService = jobService;
        this.asr = asr;
        this.codec = codec;
        this.workspace = workspace;
    }

    public MediaJob submit(String userId, TranscribeRequest request) {
        Path audio = workspace.validateInputPath(request.inputFile());
        String workId = workspace.newWorkId();
        Path transcriptPath = workspace.resolveInWorkspace(workId + "/transcript.json");

        return jobService.submit(JobType.TRANSCRIBE, userId, request.inputFile(), token -> {
            token.throwIfCancelled();
            Transcript transcript = asr.transcribe(audio, request.languageHint());
            token.throwIfCancelled();
            codec.write(transcriptPath, transcript);
            JobArtifact artifact = JobArtifact.of("transcript", transcriptPath.toString(),
                    "application/json", workspace.sizeOrZero(transcriptPath));
            return JobOutcome.of(
                    List.of(artifact),
                    Map.of("segmentCount", transcript.segments().size(),
                            "language", transcript.language(),
                            "empty", transcript.isEmpty()));
        });
    }
}
