package org.jarvis.orchestrator.resilience;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SimpleCircuitBreakerTest {

    @Test
    void startsClosedAndAllowsCalls() {
        SimpleCircuitBreaker breaker = new SimpleCircuitBreaker();

        assertThat(breaker.getState()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void opensAfterReachingFailureThreshold() {
        SimpleCircuitBreaker breaker = new SimpleCircuitBreaker();

        breaker.tryAcquire();
        breaker.recordFailure(2, Duration.ofMinutes(1));
        assertThat(breaker.getState()).isEqualTo(SimpleCircuitBreaker.State.CLOSED); // 1 failure, threshold 2

        breaker.tryAcquire();
        breaker.recordFailure(2, Duration.ofMinutes(1));
        assertThat(breaker.getState()).isEqualTo(SimpleCircuitBreaker.State.OPEN);
    }

    @Test
    void rejectsCallsWhileOpen() {
        SimpleCircuitBreaker breaker = new SimpleCircuitBreaker();
        breaker.recordFailure(1, Duration.ofMinutes(5));

        assertThat(breaker.getState()).isEqualTo(SimpleCircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
    }

    @Test
    void transitionsToHalfOpenAfterResetTimeoutAndAllowsOneTrial() {
        SimpleCircuitBreaker breaker = new SimpleCircuitBreaker();
        breaker.recordFailure(1, Duration.ofMillis(1));

        awaitElapsed(Duration.ofMillis(5));

        assertThat(breaker.getState()).isEqualTo(SimpleCircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.tryAcquire()).isTrue();
        // A second concurrent trial must not be let through while the first is in flight.
        assertThat(breaker.tryAcquire()).isFalse();
    }

    @Test
    void successfulHalfOpenTrialClosesBreaker() {
        SimpleCircuitBreaker breaker = new SimpleCircuitBreaker();
        breaker.recordFailure(1, Duration.ofMillis(1));
        awaitElapsed(Duration.ofMillis(5));

        assertThat(breaker.tryAcquire()).isTrue();
        breaker.recordSuccess();

        assertThat(breaker.getState()).isEqualTo(SimpleCircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void failedHalfOpenTrialReopensBreaker() {
        SimpleCircuitBreaker breaker = new SimpleCircuitBreaker();
        breaker.recordFailure(1, Duration.ofMillis(1));
        awaitElapsed(Duration.ofMillis(5));

        assertThat(breaker.tryAcquire()).isTrue();
        breaker.recordFailure(1, Duration.ofMinutes(5));

        assertThat(breaker.getState()).isEqualTo(SimpleCircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
    }

    private static void awaitElapsed(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
