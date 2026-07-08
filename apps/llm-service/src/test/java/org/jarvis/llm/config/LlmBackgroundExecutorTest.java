package org.jarvis.llm.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmBackgroundExecutorTest {

    @Test
    void executeRunsSubmittedTaskOnNamedDaemonThread() throws Exception {
        LlmBackgroundExecutor executor = new LlmBackgroundExecutor(1, 2, 10, 60, 20);
        try {
            CountDownLatch done = new CountDownLatch(1);
            AtomicBoolean ran = new AtomicBoolean(false);
            AtomicReference<String> threadName = new AtomicReference<>();
            AtomicBoolean daemon = new AtomicBoolean(false);

            executor.execute(() -> {
                ran.set(true);
                threadName.set(Thread.currentThread().getName());
                daemon.set(Thread.currentThread().isDaemon());
                done.countDown();
            });

            assertTrue(done.await(5, TimeUnit.SECONDS), "task should complete");
            assertTrue(ran.get());
            assertTrue(threadName.get().startsWith("llm-bg-"),
                    "thread should be named by the factory: " + threadName.get());
            assertTrue(daemon.get(), "background threads must be daemon");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void constructorRejectsNonPositiveCorePoolSize() {
        assertThrows(IllegalStateException.class,
                () -> new LlmBackgroundExecutor(0, 2, 10, 60, 20));
    }

    @Test
    void constructorRejectsMaxSmallerThanCore() {
        assertThrows(IllegalStateException.class,
                () -> new LlmBackgroundExecutor(3, 2, 10, 60, 20));
    }

    @Test
    void constructorRejectsNonPositiveQueueCapacity() {
        assertThrows(IllegalStateException.class,
                () -> new LlmBackgroundExecutor(1, 2, 0, 60, 20));
    }

    @Test
    void shutdownIsIdempotentAndSafe() {
        LlmBackgroundExecutor executor = new LlmBackgroundExecutor(1, 1, 1, 60, 1);
        executor.shutdown();
        // Second shutdown must not throw.
        executor.shutdown();
    }

    @Test
    void executeRethrowsWhenUnderlyingExecutorRejects() throws Exception {
        LlmBackgroundExecutor executor = new LlmBackgroundExecutor(1, 1, 1, 60, 20);

        // Swap in a saturated executor whose rejection policy throws, to exercise the
        // RejectedExecutionException catch/rethrow path deterministically.
        ThreadPoolExecutor abortingPool = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new ThreadPoolExecutor.AbortPolicy());
        ReflectionTestUtils.setField(executor, "executor", abortingPool);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            abortingPool.execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));

            assertThrows(RejectedExecutionException.class,
                    () -> executor.execute(() -> { }));
        } finally {
            release.countDown();
            abortingPool.shutdownNow();
        }

        assertEquals(0, abortingPool.getQueue().size());
    }
}
