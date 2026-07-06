package org.jarvis.memory.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug hunt #39 (memory-service — resource-leak): {@code AsyncConfig} was
 * {@code @EnableAsync} with no custom {@code Executor} bean, so Spring fell
 * back to {@code SimpleAsyncTaskExecutor}, which spawns a brand-new,
 * unbounded OS thread per {@code @Async} invocation (e.g. {@code
 * MemoryService.ingestAsync}) instead of reusing a pooled/bounded executor —
 * risking thread-exhaustion/OOM under a burst of concurrent calls.
 *
 * <p>Verifies {@code getAsyncExecutor()} now returns a bounded, pooled
 * executor with a real queue and a non-discarding rejection policy, rather
 * than an unbounded per-task thread spawner.</p>
 */
class AsyncConfigTest {

    private final AsyncConfig config = new AsyncConfig();

    @Test
    void asyncExecutorIsABoundedThreadPoolNotAnUnboundedPerTaskSpawner() {
        Executor executor = config.getAsyncExecutor();

        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor pooled = (ThreadPoolTaskExecutor) executor;

        assertThat(pooled.getCorePoolSize()).isGreaterThan(0);
        assertThat(pooled.getMaxPoolSize())
                .as("max pool size must be bounded, not Integer.MAX_VALUE / unbounded")
                .isGreaterThan(0)
                .isLessThan(Integer.MAX_VALUE);
        assertThat(pooled.getThreadPoolExecutor().getQueue().remainingCapacity())
                .as("must use a bounded queue, not a direct hand-off with no backing store")
                .isGreaterThan(0);
    }

    @Test
    void asyncExecutorUsesCallerRunsRejectionPolicyInsteadOfSilentlyDroppingWork() {
        ThreadPoolTaskExecutor pooled = (ThreadPoolTaskExecutor) config.getAsyncExecutor();

        assertThat(pooled.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    @Test
    void asyncUncaughtExceptionHandlerIsConfigured() {
        assertThat(config.getAsyncUncaughtExceptionHandler()).isNotNull();
    }
}
