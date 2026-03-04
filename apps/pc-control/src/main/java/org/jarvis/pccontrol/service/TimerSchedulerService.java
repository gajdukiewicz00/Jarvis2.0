package org.jarvis.pccontrol.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded timer scheduler used by pc-control timer commands.
 */
@Slf4j
@Service
public class TimerSchedulerService {

    private final ScheduledThreadPoolExecutor scheduler;
    private final int maxActiveTimers;
    private final int maxTimerSeconds;
    private final AtomicInteger activeTimers = new AtomicInteger(0);
    private final Map<String, TimerEntry> timers = new ConcurrentHashMap<>();

    public TimerSchedulerService(
            @Value("${pc.timer.pool-size:2}") int poolSize,
            @Value("${pc.timer.max-active:100}") int maxActiveTimers,
            @Value("${pc.timer.max-seconds:86400}") int maxTimerSeconds) {
        this.maxActiveTimers = maxActiveTimers;
        this.maxTimerSeconds = maxTimerSeconds;
        this.scheduler = new ScheduledThreadPoolExecutor(poolSize);
        this.scheduler.setRemoveOnCancelPolicy(true);
    }

    public String scheduleTimer(int seconds, Runnable callback) {
        if (seconds < 1 || seconds > maxTimerSeconds) {
            throw new IllegalArgumentException("Timer duration must be between 1 and " + maxTimerSeconds + " seconds");
        }
        return scheduleTimer(Duration.ofSeconds(seconds), callback);
    }

    public String scheduleTimer(Duration delay, Runnable callback) {
        if (delay.isNegative()) {
            throw new IllegalArgumentException("Timer delay must be positive");
        }

        int inFlight = activeTimers.incrementAndGet();
        if (inFlight > maxActiveTimers) {
            activeTimers.decrementAndGet();
            throw new TimerLimitExceededException("Too many active timers (" + maxActiveTimers + ")");
        }

        String timerId = UUID.randomUUID().toString();
        TimerEntry entry = new TimerEntry();
        timers.put(timerId, entry);

        Runnable wrapped = () -> {
            try {
                callback.run();
            } catch (RuntimeException e) {
                log.error("Timer callback failed: {}", e.getMessage(), e);
            } finally {
                releaseTimer(timerId, entry);
            }
        };

        try {
            long delayMs = Math.max(0L, delay.toMillis());
            entry.future = scheduler.schedule(wrapped, delayMs, TimeUnit.MILLISECONDS);
            return timerId;
        } catch (RejectedExecutionException ex) {
            releaseTimer(timerId, entry);
            throw new TimerLimitExceededException("Timer scheduler queue is full");
        }
    }

    public boolean cancelTimer(String timerId) {
        TimerEntry entry = timers.remove(timerId);
        if (entry == null) {
            return false;
        }

        ScheduledFuture<?> future = entry.future;
        if (future != null) {
            future.cancel(false);
        }
        releaseSlot(entry);
        return true;
    }

    public int getActiveTimerCount() {
        return activeTimers.get();
    }

    public int getWorkerPoolSize() {
        return scheduler.getCorePoolSize();
    }

    @PreDestroy
    public void shutdown() {
        for (Map.Entry<String, TimerEntry> entry : new ArrayList<>(timers.entrySet())) {
            cancelTimer(entry.getKey());
        }
        scheduler.shutdownNow();
    }

    private void releaseTimer(String timerId, TimerEntry entry) {
        timers.remove(timerId, entry);
        releaseSlot(entry);
    }

    private void releaseSlot(TimerEntry entry) {
        if (entry.released.compareAndSet(false, true)) {
            activeTimers.decrementAndGet();
        }
    }

    private static class TimerEntry {
        private final AtomicBoolean released = new AtomicBoolean(false);
        private ScheduledFuture<?> future;
    }
}
