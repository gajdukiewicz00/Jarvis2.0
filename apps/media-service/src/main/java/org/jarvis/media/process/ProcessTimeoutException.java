package org.jarvis.media.process;

/** Thrown when an external process exceeds its allotted timeout. */
public class ProcessTimeoutException extends RuntimeException {
    public ProcessTimeoutException(String message) {
        super(message);
    }
}
