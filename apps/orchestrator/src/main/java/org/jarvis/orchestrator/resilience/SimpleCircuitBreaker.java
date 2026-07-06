package org.jarvis.orchestrator.resilience;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal, dependency-free circuit breaker with an explicit half-open state.
 *
 * <p>Generalizes the manual failure-counting / cooldown pattern already used for
 * the LLM call in {@code OrchestratorServiceImpl}, so other downstream clients
 * (memory-service, nlp-service, ...) can reuse the same behavior without pulling
 * in a full resilience framework.</p>
 *
 * <p>State machine:</p>
 * <ul>
 *   <li>{@link State#CLOSED} - calls proceed normally.</li>
 *   <li>{@link State#OPEN} - calls are rejected immediately until the reset
 *       timeout elapses.</li>
 *   <li>{@link State#HALF_OPEN} - the reset timeout has elapsed; exactly one
 *       trial call is allowed through at a time. A success closes the breaker;
 *       a failure re-opens it for another reset timeout.</li>
 * </ul>
 *
 * <p>Thread-safe: all state is held in atomics so a single instance can be
 * shared by concurrent requests against the same dependency.</p>
 */
public final class SimpleCircuitBreaker {

    /** Observable state, primarily useful for logging/metrics and tests. */
    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicReference<Instant> openUntil = new AtomicReference<>(Instant.EPOCH);
    private final AtomicBoolean halfOpenTrialInFlight = new AtomicBoolean(false);

    /**
     * Returns {@code true} if a call may proceed. Must be paired with exactly
     * one of {@link #recordSuccess()} / {@link #recordFailure(int, Duration)}
     * when it returns {@code true}.
     */
    public boolean tryAcquire() {
        Instant until = openUntil.get();
        Instant now = Instant.now();
        if (now.isBefore(until)) {
            return false; // OPEN: still cooling down.
        }
        if (Instant.EPOCH.equals(until)) {
            return true; // CLOSED: never tripped.
        }
        // HALF_OPEN: reset window elapsed at least once; allow a single trial through.
        return halfOpenTrialInFlight.compareAndSet(false, true);
    }

    /** Records a successful call, closing the breaker. */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        openUntil.set(Instant.EPOCH);
        halfOpenTrialInFlight.set(false);
    }

    /**
     * Records a failed call. Opens (or re-opens) the breaker once
     * {@code failureThreshold} consecutive failures have been observed.
     */
    public void recordFailure(int failureThreshold, Duration resetTimeout) {
        halfOpenTrialInFlight.set(false);
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            openUntil.set(Instant.now().plus(resetTimeout));
        }
    }

    /** Current state, for logging/metrics/tests. */
    public State getState() {
        Instant until = openUntil.get();
        if (Instant.now().isBefore(until)) {
            return State.OPEN;
        }
        if (!Instant.EPOCH.equals(until)) {
            return State.HALF_OPEN;
        }
        return State.CLOSED;
    }
}
