package org.jarvis.media.ffmpeg;

import java.nio.file.Path;

/**
 * Executes ffmpeg operations. The real implementation shells out via a safe argument
 * list; the mock simulates by writing placeholder output files (never touching the
 * input), which is what runs in the binary-free container and in tests.
 */
public interface FFmpegClient {

    /** Extract one audio stream (by absolute index) to {@code output}. Input is read-only. */
    void extractAudio(Path input, int streamIndex, Path output, AudioFormat format);

    /** Mux Russian subtitle and/or audio tracks into a new {@code output}, preserving the original. */
    void mux(Path originalVideo, Path russianSubtitle, Path russianAudio, Path output);
}
