package org.jarvis.media.tts;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.media.asr.Transcript;
import org.jarvis.media.asr.TranscriptCodec;
import org.jarvis.media.asr.TranscriptSegment;
import org.jarvis.media.config.MediaProperties;
import org.jarvis.media.job.JobArtifact;
import org.jarvis.media.job.JobOutcome;
import org.jarvis.media.job.JobType;
import org.jarvis.media.job.MediaJob;
import org.jarvis.media.job.MediaJobService;
import org.jarvis.media.workspace.WorkspaceManager;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a separate Russian dubbing audio track from a Russian transcript using a
 * neutral synthetic voice. It reads ONLY the transcript text and writes ONLY into the
 * workspace — the original media and its audio are never touched.
 *
 * <p>Each cue is synthesized independently, then {@link SegmentTimingPlanner} works
 * out how to fit each clip onto its cue window (speed it up when it overruns, pad it
 * with trailing silence when it finishes early — never slow the voice down), and
 * {@link DubAudioMerger} combines every clip onto one continuous track positioned by
 * that plan. {@link DubQualityChecker} then flags missing synthesis, duration
 * mismatches, speaker overlaps, over-long cues, low-confidence source segments, and
 * overall desync risk.</p>
 */
@Slf4j
@Service
public class DubbingService {

    private final MediaJobService jobService;
    private final TranscriptCodec codec;
    private final TtsProvider tts;
    private final VoiceProfileFactory voiceProfiles;
    private final WorkspaceManager workspace;
    private final SegmentTimingPlanner timingPlanner;
    private final DubAudioMerger merger;
    private final DubQualityChecker qualityChecker;
    private final MediaProperties props;

    public DubbingService(MediaJobService jobService, TranscriptCodec codec, TtsProvider tts,
                          VoiceProfileFactory voiceProfiles, WorkspaceManager workspace,
                          SegmentTimingPlanner timingPlanner, DubAudioMerger merger,
                          DubQualityChecker qualityChecker, MediaProperties props) {
        this.jobService = jobService;
        this.codec = codec;
        this.tts = tts;
        this.voiceProfiles = voiceProfiles;
        this.workspace = workspace;
        this.timingPlanner = timingPlanner;
        this.merger = merger;
        this.qualityChecker = qualityChecker;
        this.props = props;
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
            List<TranscriptSegment> segments = transcript.segments();

            List<TtsResult> results = new ArrayList<>(segments.size());
            List<Path> segPaths = new ArrayList<>(segments.size());
            List<JobArtifact> artifacts = new ArrayList<>();

            for (TranscriptSegment seg : segments) {
                token.throwIfCancelled();
                Path segPath = workspace.resolveInWorkspace(
                        workId + "/" + String.format("seg-%04d.wav", seg.index()));
                TtsResult result = tts.synthesize(seg.text(), profile, segPath);
                results.add(result);
                segPaths.add(segPath);
                if (!result.isMissing()) {
                    artifacts.add(JobArtifact.of("dub-segment", segPath.toString(), "audio/wav", result.sizeBytes()));
                }
            }

            List<SegmentTimingInput> timingInputs = new ArrayList<>(segments.size());
            for (int i = 0; i < segments.size(); i++) {
                TranscriptSegment seg = segments.get(i);
                timingInputs.add(new SegmentTimingInput(
                        seg.index(), seg.startMs(), seg.endMs(), results.get(i).synthesizedDurationMs()));
            }
            List<SegmentTimingPlan> plans = timingPlanner.plan(timingInputs);

            List<DubSegmentAudio> mergeInputs = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                if (!results.get(i).isMissing()) {
                    mergeInputs.add(new DubSegmentAudio(segPaths.get(i), plans.get(i)));
                }
            }

            token.throwIfCancelled();
            merger.merge(mergeInputs, combined);

            DubQualityReport report = qualityChecker.check(
                    segments, results, plans, props.subtitle().maxSegmentSeconds(), props.subtitle().minConfidence());

            artifacts.add(JobArtifact.of("dub-audio", combined.toString(), "audio/wav",
                    workspace.sizeOrZero(combined)));

            return JobOutcome.of(artifacts, Map.of(
                    "voiceProfileMode", profile.mode().name(),
                    "originalAudioPreserved", true,
                    "qualityReport", report));
        });
    }
}
