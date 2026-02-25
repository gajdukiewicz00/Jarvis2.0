package org.jarvis.llm.config;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded background executor for non-critical async LLM tasks.
 */
@Slf4j
@Component
public class LlmBackgroundExecutor {

    private final ThreadPoolExecutor executor;

    public LlmBackgroundExecutor(
            @Value("${llm.executor.core-pool-size:2}") int corePoolSize,
            @Value("${llm.executor.max-pool-size:4}") int maxPoolSize,
            @Value("${llm.executor.queue-capacity:200}") int queueCapacity,
            @Value("${llm.executor.keepalive-seconds:60}") int keepaliveSeconds,
            @Value("${llm.executor.shutdown-timeout-seconds:20}") int shutdownTimeoutSeconds) {

        if (corePoolSize <= 0 || maxPoolSize < corePoolSize || queueCapacity <= 0) {
            throw new IllegalStateException("Invalid llm.executor.* configuration");
        }

        this.executor = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                keepaliveSeconds,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new NamedThreadFactory("llm-bg-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.executor.allowCoreThreadTimeOut(true);

        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;

        log.info("LLM background executor initialized: core={}, max={}, queue={}",
                corePoolSize, maxPoolSize, queueCapacity);
    }

    private final int shutdownTimeoutSeconds;

    public void execute(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("LLM background executor saturated; task rejected");
            throw e;
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("LLM background executor forced shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);
        private final String prefix;

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
