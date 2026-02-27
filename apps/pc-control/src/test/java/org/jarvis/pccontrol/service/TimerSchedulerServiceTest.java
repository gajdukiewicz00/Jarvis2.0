package org.jarvis.pccontrol.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TimerSchedulerServiceTest {

    private TimerSchedulerService timerSchedulerService;

    @AfterEach
    void tearDown() {
        if (timerSchedulerService != null) {
            timerSchedulerService.shutdown();
        }
    }

    @Test
    void shouldRejectWhenActiveTimerLimitExceeded() {
        timerSchedulerService = new TimerSchedulerService(1, 3, 86_400);

        String t1 = timerSchedulerService.scheduleTimer(Duration.ofMinutes(5), () -> {});
        String t2 = timerSchedulerService.scheduleTimer(Duration.ofMinutes(5), () -> {});
        String t3 = timerSchedulerService.scheduleTimer(Duration.ofMinutes(5), () -> {});

        TimerLimitExceededException exception = assertThrows(
                TimerLimitExceededException.class,
                () -> timerSchedulerService.scheduleTimer(Duration.ofMinutes(5), () -> {}));
        assertTrue(exception.getMessage().contains("Too many active timers"));
        assertEquals(3, timerSchedulerService.getActiveTimerCount());

        assertTrue(timerSchedulerService.cancelTimer(t1));
        assertTrue(timerSchedulerService.cancelTimer(t2));
        assertTrue(timerSchedulerService.cancelTimer(t3));
    }

    @Test
    void shouldUseBoundedWorkerThreadsUnderLoad() throws Exception {
        timerSchedulerService = new TimerSchedulerService(2, 64, 86_400);

        int taskCount = 24;
        CountDownLatch done = new CountDownLatch(taskCount);
        Set<String> threadNames = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < taskCount; i++) {
            timerSchedulerService.scheduleTimer(Duration.ofMillis(10), () -> {
                threadNames.add(Thread.currentThread().getName());
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(5, TimeUnit.SECONDS), "Timers did not finish in time");
        assertTrue(
                threadNames.size() <= timerSchedulerService.getWorkerPoolSize(),
                "Expected bounded worker threads, got " + threadNames.size());
    }
}
