package org.jarvis.media.tts;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.media.asr.Transcript;
import org.jarvis.media.asr.TranscriptCodec;
import org.jarvis.media.asr.TranscriptSegment;
import org.jarvis.media.job.JobArtifact;
import org.jarvis.media.job.JobOutcome;
import org.jarvis.media.job.JobType;
import org.jarvis.media.job.MediaJob;
import org.jarvis.media.job.MediaJobService;
import org.jarvis.media.workspace.WorkspaceManager;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a separate Russian dubbing audio track from a Russian transcript using a
 * neutral synthetic voice. It reads ONLY the transcript text and writes ONLY into the
 * workspace — the original media and its audio are never touched. A quality report
 * flags missing synthesis, duration mismatches, speaker overlaps, and desync risk.
 */
@Slf4j
@Service
public class DubbingService {

    private static final long MISMATCH_FLOOR_MS = 1500;
    private static final long SYNC_RISK_DRIFT_MS = 3000;

    private final MediaJobService jobService;
    private final TranscriptCodec codec;
    private final TtsProvider tts;
    private final VoiceProfileFactory voiceProfiles;
    private final WorkspaceManager workspace;

    public DubbingService(MediaJobService jobService, TranscriptCodec codec, TtsProvider tts,
                          VoiceProfileFactory voiceProfiles, WorkspaceManager workspace) {
        this.jobService = jobService;
        this.codec = codec;
        this.tts = tts;
        this.voiceProfiles = voiceProfiles;
        this.workspace = workspace;
    }

    public MediaJob submit(String userId, RussianDubRequest request) {
        Path transcriptPath = workspace.validateInputPath(request.transcriptFile());
        // Consent/enablement enforced synchronously so unauthorized voice use fails fast.
        VoiceProfile profile = voiceProfiles.resolve(
                request.voiceProfileMode(), request.voiceId(), request.consentConfirmed());
        String workId = workspace.newWorkId();
        Path combined = workspace.resolveInWorkspace(workId + "/dub.ru.wav");

        return jobService.submit(JobType.RUSSIAN_DUB_AUDIO, userId, request.transcriptFile(), token -> {
            token.throwIfCancelled();
            Transcript transcript = codec.read(transcriptPath);

            List<JobArtifact> artifacts = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            int missing = 0;
            int mismatches = 0;
            int overlaps = 0;
            long totalDrift = 0;
            TranscriptSegment previous = null;

            for (TranscriptSegment seg : transcript.segments()) {
                token.throwIfCancelled();
                Path segPath = workspace.resolveInWorkspace(
                        workId + "/" + String.format("seg-%04d.wav", seg.index()));
                TtsResult result = tts.synthesize(seg.text(), profile, segPath);

                if (result.isMissing()) {
                    missing++;
                    notes.add("Segment " + seg.index() + ": no TTS audio produced");
                } else {
                    artifacts.add(JobArtifact.of("dub-segment", segPath.toString(), "audio/wav", result.sizeBytes()));
                }

                long cueMs = seg.durationMs();
                long drift = result.synthesizedDurationMs() - cueMs;
                totalDrift += drift;
                if (Math.abs(drift) > Math.max(MISMATCH_FLOOR_MS, cueMs / 2)) {
                    mismatches++;
                    notes.add("Segment " + seg.index() + ": dubbed " + result.synthesizedDurationMs()
                            + "ms vs cue " + cueMs + "ms");
                }
                if (previous != null && seg.startMs() < previous.endMs()
                        && !sameSpeaker(previous.speakerId(), seg.speakerId())) {
                    overlaps++;
                    notes.add("Segment " + seg.index() + ": speaker overlap with " + previous.index());
                }
                previous = seg;
            }

            boolean badSyncRisk = Math.abs(totalDrift) > SYNC_RISK_DRIFT_MS;
            DubQualityReport report = new DubQualityReport(
                    transcript.segments().size(), missing, mismatches, overlaps, badSyncRisk, notes);

            // Combined track: in mock mode a manifest placeholder; real mode would concat with ffmpeg.
            writeCombinedPlaceholder(combined, transcript.segments().size(), profile);
            artifacts.add(JobArtifact.of("dub-audio", combined.toString(), "audio/wav",
                    workspace.sizeOrZero(combined)));

            return JobOutcome.of(artifacts, Map.of(
                    "voiceProfileMode", profile.mode().name(),
                    "originalAudioPreserved", true,
                    "qualityReport", report));
        });
    }

    private boolean sameSpeaker(String a, String b) {
        return a != null && a.equals(b);
    }

    private void writeCombinedPlaceholder(Path combined, int segmentCount, VoiceProfile profile) {
        String marker = "MOCK-DUB-COMBINED voice=" + profile.mode() + " segments=" + segmentCount + "\n";
        try {
            Files.writeString(combined, marker, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TtsException("could not write combined dub track: " + e.getMessage());
        }
    }
}
