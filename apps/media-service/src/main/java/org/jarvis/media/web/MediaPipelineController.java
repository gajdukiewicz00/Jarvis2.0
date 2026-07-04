package org.jarvis.media.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.jarvis.media.asr.TranscribeRequest;
import org.jarvis.media.asr.TranscriptionService;
import org.jarvis.media.ffmpeg.AudioExtractionService;
import org.jarvis.media.ffmpeg.ExtractAudioRequest;
import org.jarvis.media.mux.MuxRequest;
import org.jarvis.media.mux.MuxService;
import org.jarvis.media.subtitle.RussianSubtitleRequest;
import org.jarvis.media.subtitle.SubtitleService;
import org.jarvis.media.tts.DubbingService;
import org.jarvis.media.tts.RussianDubRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Async media-pipeline endpoints. Each accepts a request, schedules a job, and returns
 * the CREATED job (HTTP 202) immediately — request threads never block on media work.
 */
@RestController
@RequestMapping("/api/v1/media/jobs")
public class MediaPipelineController {

    private final AudioExtractionService audioExtraction;
    private final TranscriptionService transcription;
    private final SubtitleService subtitles;
    private final DubbingService dubbing;
    private final MuxService mux;
    private final MediaFeatureGate gate;

    public MediaPipelineController(AudioExtractionService audioExtraction, TranscriptionService transcription,
                                   SubtitleService subtitles, DubbingService dubbing, MuxService mux,
                                   MediaFeatureGate gate) {
        this.audioExtraction = audioExtraction;
        this.transcription = transcription;
        this.subtitles = subtitles;
        this.dubbing = dubbing;
        this.mux = mux;
        this.gate = gate;
    }

    @PostMapping("/extract-audio")
    public ResponseEntity<JobView> extractAudio(@Valid @RequestBody ExtractAudioRequest request,
                                                HttpServletRequest http) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(http);
        return accepted(JobView.from(audioExtraction.submit(userId, request)));
    }

    @PostMapping("/transcribe")
    public ResponseEntity<JobView> transcribe(@Valid @RequestBody TranscribeRequest request,
                                              HttpServletRequest http) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(http);
        return accepted(JobView.from(transcription.submit(userId, request)));
    }

    @PostMapping("/russian-subtitles")
    public ResponseEntity<JobView> russianSubtitles(@Valid @RequestBody RussianSubtitleRequest request,
                                                    HttpServletRequest http) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(http);
        return accepted(JobView.from(subtitles.submit(userId, request)));
    }

    @PostMapping("/russian-dub-audio")
    public ResponseEntity<JobView> russianDubAudio(@Valid @RequestBody RussianDubRequest request,
                                                   HttpServletRequest http) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(http);
        return accepted(JobView.from(dubbing.submit(userId, request)));
    }

    @PostMapping("/mux")
    public ResponseEntity<JobView> muxTracks(@Valid @RequestBody MuxRequest request, HttpServletRequest http) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(http);
        return accepted(JobView.from(mux.submit(userId, request)));
    }

    private ResponseEntity<JobView> accepted(JobView job) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
    }
}
