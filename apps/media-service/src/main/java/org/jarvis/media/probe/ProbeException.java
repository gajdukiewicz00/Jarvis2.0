package org.jarvis.media.probe;

/** Thrown when ffprobe output is missing, malformed, or otherwise unusable. */
public class ProbeException extends RuntimeException {
    public ProbeException(String message) {
        super(message);
    }

    public ProbeException(String message, Throwable cause) {
        super(message, cause);
    }
}
