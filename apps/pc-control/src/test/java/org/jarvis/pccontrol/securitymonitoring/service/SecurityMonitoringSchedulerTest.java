package org.jarvis.pccontrol.securitymonitoring.service;

import org.jarvis.pccontrol.securitymonitoring.config.SecurityMonitoringProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class SecurityMonitoringSchedulerTest {

    @Mock
    private SecurityMonitoringService securityMonitoringService;

    @Test
    void continuesSchedulingEvenWhenOneCheckThrows() throws Exception {
        SecurityMonitoringProperties properties = new SecurityMonitoringProperties();
        properties.setSamplingInterval(Duration.ofMillis(10));
        SecurityMonitoringScheduler scheduler = new SecurityMonitoringScheduler(securityMonitoringService, properties);

        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger invocations = new AtomicInteger();
        doAnswer(invocation -> {
            int count = invocations.incrementAndGet();
            latch.countDown();
            if (count == 1) {
                throw new RuntimeException("boom");
            }
            return null;
        }).when(securityMonitoringService).runCheck("scheduled");

        try {
            scheduler.start();
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(invocations.get()).isGreaterThanOrEqualTo(2);
        } finally {
            scheduler.stop();
        }
    }
}
