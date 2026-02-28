package org.jarvis.common.exception;

/**
 * Thrown when an idempotency key conflict is detected during tool request processing.
 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}

