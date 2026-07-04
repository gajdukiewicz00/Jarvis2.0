package org.jarvis.swarm.sandbox;

/** Thrown on sandbox path-traversal/escape attempts or sandbox I/O errors. Mapped to HTTP 400. */
public class SandboxException extends RuntimeException {
    public SandboxException(String message) {
        super(message);
    }
}
