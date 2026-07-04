package org.jarvis.media.subtitle;

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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates Russian subtitles (SRT + VTT) from a transcript. Each transcript segment
 * is run through {@link MediaTextGuard} BEFORE translation, so injection markers in
 * the source media are neutralized and never reach an (optionally LLM-backed)
 * translator as instructions. Timing is preserved exactly; quality warnings are
 * attached to the job details.
 */
@Service
public class SubtitleService {

    private final MediaJobService jobService;
    private final TranscriptCodec codec;
    private final MediaTextGuard guard;
    private final TranslationProvider translator;
    private final SubtitleFormatter formatter;
    private final SubtitleQualityChecker qualityChecker;
    private final WorkspaceManager workspace;
    private final MediaProperties props;

    public SubtitleService(MediaJobService jobService, TranscriptCodec codec, MediaTextGuard guard,
                           TranslationProvider translator, SubtitleFormatter formatter,
                           SubtitleQualityChecker qualityChecker, WorkspaceManager workspace,
                           MediaProperties props) {
        this.jobService = jobService;
        this.codec = codec;
        this.guard = guard;
        this.translator = translator;
        this.formatter = formatter;
        this.qualityChecker = qualityChecker;
        this.workspace = workspace;
        this.props = props;
    }

    public MediaJob submit(String userId, RussianSubtitleRequest request) {
        Path transcriptPath = workspace.validateInputPath(request.transcriptFile());
        String workId = workspace.newWorkId();
        Path srtPath = workspace.resolveInWorkspace(workId + "/subtitles.ru.srt");
        Path vttPath = workspace.resolveInWorkspace(workId + "/subtitles.ru.vtt");

        Path ruTranscriptPath = workspace.resolveInWorkspace(workId + "/transcript.ru.json");

        return jobService.submit(JobType.RUSSIAN_SUBTITLES, userId, request.transcriptFile(), token -> {
            token.throwIfCancelled();
            Transcript transcript = codec.read(transcriptPath);
            List<TranslatedSegment> translated = translate(transcript, token);

            List<SubtitleWarning> warnings = qualityChecker.check(
                    translated, props.subtitle().maxSegmentSeconds(), props.subtitle().minConfidence());

            List<TranslatedSegment> renderable = translated.stream().filter(s -> !s.isBlank()).toList();
            write(srtPath, formatter.toSrt(renderable));
            write(vttPath, formatter.toVtt(renderable));
            // Russian transcript artifact feeds the dubbing step (C6).
            codec.write(ruTranscriptPath, toRussianTranscript(translated));

            return JobOutcome.of(
                    List.of(
                            JobArtifact.of("subtitle-srt", srtPath.toString(), "application/x-subrip",
                                    workspace.sizeOrZero(srtPath)),
                            JobArtifact.of("subtitle-vtt", vttPath.toString(), "text/vtt",
                                    workspace.sizeOrZero(vttPath)),
                            JobArtifact.of("transcript-ru", ruTranscriptPath.toString(), "application/json",
                                    workspace.sizeOrZero(ruTranscriptPath))),
                    Map.of("segmentCount", translated.size(),
                            "warningCount", warnings.size(),
                            "warnings", warnings));
        });
    }

    private List<TranslatedSegment> translate(Transcript transcript, org.jarvis.media.job.CancellationToken token) {
        List<TranslatedSegment> out = new ArrayList<>();
        for (TranscriptSegment seg : transcript.segments()) {
            token.throwIfCancelled();
            // Trust boundary: neutralize untrusted media text before any translation/LLM use.
            String neutralized = guard.neutralize(seg.text());
            String russian = translator.translate(neutralized, "ru");
            out.add(new TranslatedSegment(seg.index(), seg.startMs(), seg.endMs(), russian,
                    seg.speakerId(), seg.confidence()));
        }
        return out;
    }

    private Transcript toRussianTranscript(List<TranslatedSegment> translated) {
        List<TranscriptSegment> ru = translated.stream()
                .map(t -> new TranscriptSegment(t.index(), t.startMs(), t.endMs(), t.text(),
                        t.speakerId(), t.confidence()))
                .toList();
        return new Transcript("ru", ru);
    }

    private void write(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write subtitle file: " + e.getMessage());
        }
    }
}
