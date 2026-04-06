package org.jarvis.pccontrol.securitymonitoring.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.securitymonitoring.config.SecurityMonitoringProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pc-control.security-monitoring", name = "enabled", havingValue = "true")
public class SecurityMonitoringScheduler {

    private final SecurityMonitoringService securityMonitoringService;
    private final SecurityMonitoringProperties properties;

    private final AtomicBoolean started = new AtomicBoolean();
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "jarvis-security-monitor");
        thread.setDaemon(true);
        return thread;
    });

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        long intervalMillis = Math.max(500L, properties.getSamplingInterval().toMillis());
        executorService.scheduleWithFixedDelay(
                () -> {
                    try {
                        securityMonitoringService.runCheck("scheduled");
                    } catch (Exception exception) {
                        log.error("Security monitoring scheduled check failed", exception);
                    }
                },
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS);
        log.info("Security monitoring scheduler started with interval {} ms", intervalMillis);
    }

    @PreDestroy
    public void stop() {
        executorService.shutdownNow();
    }
}
