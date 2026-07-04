package org.jarvis.media.ffmpeg;

/** Thrown when an ffmpeg operation fails. */
public class FFmpegException extends RuntimeException {
    public FFmpegException(String message) {
        super(message);
    }
}
