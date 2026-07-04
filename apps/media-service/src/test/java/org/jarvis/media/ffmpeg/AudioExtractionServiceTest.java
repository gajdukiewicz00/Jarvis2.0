package org.jarvis.media.ffmpeg;

import org.jarvis.media.job.JobStatus;
import org.jarvis.media.job.MediaJob;
import org.jarvis.media.job.MediaJobService;
import org.jarvis.media.job.MediaJobStore;
import org.jarvis.media.probe.ProbeRequest;
import org.jarvis.media.probe.ProbeResult;
import org.jarvis.media.probe.ProbeService;
import org.jarvis.media.support.MediaTestFactory;
import org.jarvis.media.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AudioExtractionServiceTest {

    @TempDir
    Path tmp;

    private AudioExtractionService service(FFmpegClient ffmpeg, ProbeService probe) {
        MediaJobStore store = MediaTestFactory.store();
        MediaJobService jobs = MediaTestFactory.syncJobService(store);
        WorkspaceManager ws = MediaTestFactory.workspace(tmp);
        return new AudioExtractionService(jobs, probe, ffmpeg, ws);
    }

    @Test
    void extractsExplicitStreamAndRecordsArtifactWithoutTouchingInput() {
        FFmpegClient ffmpeg = mock(FFmpegClient.class);
        ProbeService probe = mock(ProbeService.class);
        AudioExtractionService service = service(ffmpeg, probe);

        String input = tmp.resolve("movie.mkv").toString();
        MediaJob job = service.submit("u1", new ExtractAudioRequest(input, 1, "wav", null));

        assertThat(job.status()).isIn(JobStatus.CREATED, JobStatus.COMPLETED);

        ArgumentCaptor<Path> inputCap = ArgumentCaptor.forClass(Path.class);
        ArgumentCaptor<Path> outputCap = ArgumentCaptor.forClass(Path.class);
        verify(ffmpeg).extractAudio(inputCap.capture(), eq(1), outputCap.capture(), eq(AudioFormat.WAV));

        // input is the validated source; output is a distinct workspace file (original preserved)
        assertThat(inputCap.getValue().toString()).isEqualTo(tmp.resolve("movie.mkv").toString());
        assertThat(outputCap.getValue()).isNotEqualTo(inputCap.getValue());
        assertThat(outputCap.getValue().startsWith(tmp.toAbsolutePath().normalize())).isTrue();
    }

    @Test
    void autoSelectsMainAudioWhenIndexOmitted() {
        FFmpegClient ffmpeg = mock(FFmpegClient.class);
        ProbeService probe = mock(ProbeService.class);
        when(probe.probe(any(ProbeRequest.class)))
                .thenReturn(new ProbeResult(java.util.List.of(), java.util.List.of(),
                        java.util.List.of(), 2, 1800.0));
        AudioExtractionService service = service(ffmpeg, probe);

        String input = tmp.resolve("movie.mkv").toString();
        service.submit("u1", new ExtractAudioRequest(input, null, "flac", "eng"));

        verify(probe).probe(any(ProbeRequest.class));
        verify(ffmpeg).extractAudio(any(), eq(2), any(), eq(AudioFormat.FLAC));
    }

    @Test
    void invalidFormatFailsFastBeforeScheduling() {
        AudioExtractionService service = service(mock(FFmpegClient.class), mock(ProbeService.class));
        String input = tmp.resolve("movie.mkv").toString();
        assertThatThrownBy(() -> service.submit("u1", new ExtractAudioRequest(input, 1, "mp3", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
