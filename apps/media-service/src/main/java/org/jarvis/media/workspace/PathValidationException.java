package org.jarvis.media.workspace;

/**
 * Thrown when a supplied media path fails validation (traversal attempt, escape
 * from the allowed roots, null byte, or blank value). Mapped to HTTP 400.
 */
public class PathValidationException extends RuntimeException {
    public PathValidationException(String message) {
        super(message);
    }
}
