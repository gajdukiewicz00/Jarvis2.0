package org.jarvis.media.tts;

import org.jarvis.media.asr.Transcript;
import org.jarvis.media.asr.TranscriptCodec;
import org.jarvis.media.asr.TranscriptSegment;
import org.jarvis.media.job.JobStatus;
import org.jarvis.media.job.MediaJob;
import org.jarvis.media.job.MediaJobService;
import org.jarvis.media.job.MediaJobStore;
import org.jarvis.media.support.MediaTestFactory;
import org.jarvis.media.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DubbingServiceTest {

    @TempDir
    Path tmp;

    private record Harness(DubbingService service, MediaJobService jobs, TranscriptCodec codec) {}

    private Harness harness() {
        MediaJobStore store = MediaTestFactory.store();
        MediaJobService jobs = MediaTestFactory.syncJobService(store);
        WorkspaceManager ws = MediaTestFactory.workspace(tmp);
        TranscriptCodec codec = new TranscriptCodec();
        VoiceProfileFactory voices = new VoiceProfileFactory(MediaTestFactory.props(tmp));
        DubbingService service = new DubbingService(jobs, codec, new NeutralRussianTtsProvider(), voices, ws);
        return new Harness(service, jobs, codec);
    }

    private Path writeRu(TranscriptCodec codec, List<TranscriptSegment> segments) {
        Path file = tmp.resolve("transcript.ru.json");
        codec.write(file, new Transcript("ru", segments));
        return file;
    }

    @Test
    void producesCombinedDubAudioAndQualityReportOriginalUntouched() {
        Harness h = harness();
        Path ru = writeRu(h.codec(), List.of(
                new TranscriptSegment(0, 0, 2500, "Привет", "S1", 0.9),
                new TranscriptSegment(1, 2600, 6000, "Добрый вечер", "S1", 0.9)));

        MediaJob created = h.service().submit("u1", new RussianDubRequest(ru.toString(), "neutral", null, false));
        MediaJob done = h.jobs().getJob(created.id(), "u1");

        assertThat(done.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(done.outputFiles()).anyMatch(a -> a.kind().equals("dub-audio"));
        assertThat(done.details()).containsEntry("originalAudioPreserved", true);
        assertThat(done.details()).containsKey("qualityReport");
    }

    @Test
    void ttsFailureMarksJobFailed() {
        Harness h = harness();
        Path ru = writeRu(h.codec(), List.of(
                new TranscriptSegment(0, 0, 2500, "this will tts-fail", "S1", 0.9)));

        MediaJob created = h.service().submit("u1", new RussianDubRequest(ru.toString(), "neutral", null, false));
        MediaJob done = h.jobs().getJob(created.id(), "u1");

        assertThat(done.status()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void detectsDurationMismatch() {
        Harness h = harness();
        // tiny cue (200ms) but long text -> synthesized duration far exceeds the cue
        String longText = "Это очень длинная реплика которая точно не уместится в крошечный интервал времени";
        Path ru = writeRu(h.codec(), List.of(
                new TranscriptSegment(0, 0, 200, longText, "S1", 0.9)));

        MediaJob created = h.service().submit("u1", new RussianDubRequest(ru.toString(), "neutral", null, false));
        MediaJob done = h.jobs().getJob(created.id(), "u1");

        DubQualityReport report = (DubQualityReport) done.details().get("qualityReport");
        assertThat(report).isNotNull();
        assertThat(report.durationMismatches()).isGreaterThan(0);
    }
}
