package org.jarvis.orchestrator.resilience;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Bounded retry with exponential backoff for idempotent, read-only downstream
 * calls (e.g. a search/analyze lookup). Not intended for calls with side
 * effects: retrying a non-idempotent write can duplicate the effect.
 */
public final class RetryWithBackoff {

    private RetryWithBackoff() {
    }

    /**
     * Invokes {@code operation} up to {@code maxAttempts} times, doubling the
     * wait between attempts starting at {@code initialBackoff}. Rethrows the
     * last failure once attempts are exhausted.
     *
     * @param maxAttempts    total attempts including the first (must be >= 1)
     * @param initialBackoff wait before the 2nd attempt; doubles each retry
     */
    public static <T> T call(Supplier<T> operation, int maxAttempts, Duration initialBackoff) {
        int attempts = Math.max(1, maxAttempts);
        Duration backoff = initialBackoff == null ? Duration.ZERO : initialBackoff;
        RuntimeException lastFailure = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return operation.get();
            } catch (RuntimeException ex) {
                lastFailure = ex;
                if (attempt == attempts) {
                    break;
                }
                sleep(backoff);
                backoff = backoff.multipliedBy(2);
            }
        }
        throw lastFailure;
    }

    private static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Retry backoff interrupted", interrupted);
        }
    }
}
