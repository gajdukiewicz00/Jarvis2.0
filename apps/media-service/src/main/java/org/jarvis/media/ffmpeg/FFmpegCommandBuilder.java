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
     */
    public List<String> mux(String binary, Path originalVideo, Path russianSubtitle, Path russianAudio, Path output) {
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
            args.add("-metadata:s:a:1");
            args.add("language=rus");
            args.add("-metadata:s:a:1");
            args.add("title=Russian (neutral TTS)");
        }
        if (subInput != null) {
            args.add("-metadata:s:s:0");
            args.add("language=rus");
        }
        args.add(output.toString());
        return List.copyOf(args);
    }
}
