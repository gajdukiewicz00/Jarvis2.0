package org.jarvis.media.web;

/** Thrown when {@code media.enabled=false} and a job endpoint is called. Mapped to HTTP 503. */
public class MediaDisabledException extends RuntimeException {
    public MediaDisabledException() {
        super("Media service is disabled");
    }
}
