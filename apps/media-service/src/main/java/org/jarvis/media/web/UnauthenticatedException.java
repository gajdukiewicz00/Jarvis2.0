package org.jarvis.media.web;

/** Thrown when a request lacks the gateway-injected user identity. Mapped to HTTP 401. */
public class UnauthenticatedException extends RuntimeException {
    public UnauthenticatedException() {
        super("Missing user identity");
    }
}
