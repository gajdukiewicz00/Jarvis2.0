package org.jarvis.media.ffmpeg;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds ffmpeg argument lists as pure, side-effect-free functions. Every path and
 * option is a separate list element — there is no string concatenation and no shell,
 * so user-controlled filenames cannot inject arguments or commands. This class is the
 * unit-tested heart of "no shell injection".
 */
@Component
public class FFmpegCommandBuilder {

    /**
     * Extract a single audio stream (by absolute index) to a lossless file, never
     * touching the input. {@code -map 0:<index>} selects exactly that stream.
     */
    public List<String> extractAudio(String binary, Path input, int streamIndex, Path output, AudioFormat format) {
        if (streamIndex < 0) {
            throw new IllegalArgumentException("streamIndex must be >= 0");
        }
        List<String> args = new ArrayList<>();
        args.add(binary);
        args.add("-hide_banner");
        args.add("-nostdin");
        args.add("-y");
        args.add("-i");
        args.add(input.toString());
        args.add("-map");
        args.add("0:" + streamIndex);
        args.add("-vn");
        args.add("-acodec");
        args.add(format.codec());
        args.add(output.toString());
        return List.copyOf(args);
    }

    /**
     * Mux a Russian subtitle track and/or a Russian audio track into a COPY of the
     * original, copying every original stream so nothing is lost or re-encoded. The
     * original file is an input only; the output is a new file.
     *
     * <p>{@code -metadata:s:a:N} / {@code -metadata:s:s:N} specifiers are relative to the
     * *output's* type-ordering, not the input's absolute stream index. Since {@code -map 0}
     * copies every original stream first and the new Russian track(s) are appended after,
     * the new audio track lands at output index {@code originalAudioStreamCount} (not a
     * fixed {@code 1}) and the new subtitle track lands at {@code originalSubtitleStreamCount}
     * (not a fixed {@code 0}). Callers must pass the original file's actual per-type stream
     * counts (e.g. via a prior FFprobe call) rather than assuming exactly one pre-existing
     * audio stream and zero pre-existing subtitle streams.
     */
    public List<String> mux(String binary, Path originalVideo, Path russianSubtitle, Path russianAudio,
                             int originalAudioStreamCount, int originalSubtitleStreamCount, Path output) {
        if (originalAudioStreamCount < 0) {
            throw new IllegalArgumentException("originalAudioStreamCount must be >= 0");
        }
        if (originalSubtitleStreamCount < 0) {
            throw new IllegalArgumentException("originalSubtitleStreamCount must be >= 0");
        }
        List<String> args = new ArrayList<>();
        args.add(binary);
        args.add("-hide_banner");
        args.add("-nostdin");
        args.add("-y");
        // input 0: original
        args.add("-i");
        args.add(originalVideo.toString());
        int nextInput = 1;
        Integer subInput = null;
        Integer audioInput = null;
        if (russianSubtitle != null) {
            args.add("-i");
            args.add(russianSubtitle.toString());
            subInput = nextInput++;
        }
        if (russianAudio != null) {
            args.add("-i");
            args.add(russianAudio.toString());
            audioInput = nextInput++;
        }
        // keep every original stream
        args.add("-map");
        args.add("0");
        if (audioInput != null) {
            args.add("-map");
            args.add(audioInput + ":a");
        }
        if (subInput != null) {
            args.add("-map");
            args.add(subInput + ":s");
        }
        // copy original streams; tag the added Russian tracks
        args.add("-c");
        args.add("copy");
        if (audioInput != null) {
            String audioSpecifier = "-metadata:s:a:" + originalAudioStreamCount;
            args.add(audioSpecifier);
            args.add("language=rus");
            args.add(audioSpecifier);
            args.add("title=Russian (neutral TTS)");
        }
        if (subInput != null) {
            args.add("-metadata:s:s:" + originalSubtitleStreamCount);
            args.add("language=rus");
        }
        args.add(output.toString());
        return List.copyOf(args);
    }
}
