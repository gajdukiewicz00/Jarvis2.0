package org.jarvis.orchestrator.resilience;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryWithBackoffTest {

    @Test
    void returnsResultImmediatelyWhenFirstAttemptSucceeds() {
        AtomicInteger calls = new AtomicInteger();

        String result = RetryWithBackoff.call(() -> {
            calls.incrementAndGet();
            return "ok";
        }, 3, Duration.ofMillis(1));

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void retriesUntilSuccessWithinBoundedAttempts() {
        AtomicInteger calls = new AtomicInteger();

        String result = RetryWithBackoff.call(() -> {
            int attempt = calls.incrementAndGet();
            if (attempt < 3) {
                throw new RuntimeException("transient failure " + attempt);
            }
            return "recovered";
        }, 5, Duration.ofMillis(1));

        assertThat(result).isEqualTo("recovered");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void throwsLastFailureAfterExhaustingAttempts() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> RetryWithBackoff.call(() -> {
            calls.incrementAndGet();
            throw new RuntimeException("boom");
        }, 3, Duration.ofMillis(1)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void singleAttemptDoesNotRetry() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> RetryWithBackoff.call(() -> {
            calls.incrementAndGet();
            throw new RuntimeException("no retry");
        }, 1, Duration.ofMillis(50)))
                .isInstanceOf(RuntimeException.class);

        assertThat(calls.get()).isEqualTo(1);
    }
}
