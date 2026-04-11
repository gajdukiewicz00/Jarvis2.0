package org.jarvis.llm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Controls concurrent access to LLM inference.
 * Prevents VRAM thrash and inference storms on single-GPU hardware.
 *
 * Priority levels: VOICE (highest), INTERACTIVE (medium), BACKGROUND (lowest).
 * Voice requests can preempt the wait queue; background requests are rejected
 * immediately when the queue is full.
 */
@Slf4j
@Component
public class LlmAdmissionController {

    public enum Priority {
        VOICE(0),
        INTERACTIVE(1),
        BACKGROUND(2);

        final int level;
        Priority(int level) { this.level = level; }
    }

    private final Semaphore inferenceSemaphore;
    private final int maxQueueDepth;
    private final AtomicInteger queueDepth = new AtomicInteger(0);
    private final AtomicInteger activeInferences = new AtomicInteger(0);
    private final AtomicLong rejectedCount = new AtomicLong(0);
    private final AtomicLong timeoutCount = new AtomicLong(0);
    private final AtomicLong totalAdmitted = new AtomicLong(0);

    public LlmAdmissionController(
            @Value("${llm.admission.max-concurrent:1}") int maxConcurrent,
            @Value("${llm.admission.max-queue-depth:8}") int maxQueueDepth) {
        this.inferenceSemaphore = new Semaphore(maxConcurrent, true);
        this.maxQueueDepth = maxQueueDepth;
        log.info("LLM admission controller: maxConcurrent={}, maxQueueDepth={}", maxConcurrent, maxQueueDepth);
    }

    /**
     * Attempt to acquire an inference slot.
     *
     * @param priority request priority
     * @param timeoutSeconds how long to wait
     * @return an AdmissionTicket if acquired, or null if rejected/timed out
     */
    public AdmissionTicket tryAcquire(Priority priority, int timeoutSeconds) {
        int currentQueue = queueDepth.get();
        int available = inferenceSemaphore.availablePermits();

        if (priority == Priority.BACKGROUND && (available == 0 || currentQueue >= maxQueueDepth)) {
            rejectedCount.incrementAndGet();
            log.warn("LLM admission REJECTED: background request (available={}, queue={}/{})",
                    available, currentQueue, maxQueueDepth);
            return null;
        }

        if (priority != Priority.VOICE && currentQueue >= maxQueueDepth * 2) {
            rejectedCount.incrementAndGet();
            log.warn("LLM admission REJECTED: queue overflow ({}/{})", currentQueue, maxQueueDepth * 2);
            return null;
        }

        queueDepth.incrementAndGet();
        try {
            boolean acquired = inferenceSemaphore.tryAcquire(timeoutSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                timeoutCount.incrementAndGet();
                log.warn("LLM admission TIMEOUT: priority={}, waited {}s", priority, timeoutSeconds);
                return null;
            }
            activeInferences.incrementAndGet();
            totalAdmitted.incrementAndGet();
            return new AdmissionTicket(this);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            timeoutCount.incrementAndGet();
            return null;
        } finally {
            queueDepth.decrementAndGet();
        }
    }

    void release() {
        activeInferences.decrementAndGet();
        inferenceSemaphore.release();
    }

    public int getActiveInferences() { return activeInferences.get(); }
    public int getQueueDepth() { return queueDepth.get(); }
    public long getRejectedCount() { return rejectedCount.get(); }
    public long getTimeoutCount() { return timeoutCount.get(); }
    public long getTotalAdmitted() { return totalAdmitted.get(); }
    public int getAvailablePermits() { return inferenceSemaphore.availablePermits(); }

    public static class AdmissionTicket implements AutoCloseable {
        private final LlmAdmissionController controller;
        private volatile boolean released = false;

        AdmissionTicket(LlmAdmissionController controller) {
            this.controller = controller;
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                controller.release();
            }
        }
    }
}
