package org.jarvis.analytics.config;

import feign.FeignException;
import feign.RetryableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.analytics.client.LifeTrackerClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health indicator that checks life-tracker service availability.
 * 
 * This is critical for analytics-service as it depends on life-tracker
 * for all data fetching operations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LifeTrackerHealthIndicator implements HealthIndicator {

    private final LifeTrackerClient lifeTrackerClient;

    @Override
    public Health health() {
        try {
            // Try to fetch a small amount of data to verify connectivity
            long startTime = System.currentTimeMillis();
            lifeTrackerClient.getExpenses();
            long responseTime = System.currentTimeMillis() - startTime;
            
            return Health.up()
                    .withDetail("service", "life-tracker")
                    .withDetail("status", "reachable")
                    .withDetail("responseTimeMs", responseTime)
                    .build();
                    
        } catch (RetryableException e) {
            log.warn("Life-tracker health check failed (timeout): {}", e.getMessage());
            return Health.down()
                    .withDetail("service", "life-tracker")
                    .withDetail("status", "timeout")
                    .withDetail("error", "Connection timeout")
                    .build();
                    
        } catch (FeignException e) {
            log.warn("Life-tracker health check failed [{}]: {}", e.status(), e.getMessage());
            return Health.down()
                    .withDetail("service", "life-tracker")
                    .withDetail("status", "error")
                    .withDetail("httpStatus", e.status())
                    .withDetail("error", e.getMessage())
                    .build();
                    
        } catch (Exception e) {
            log.error("Life-tracker health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("service", "life-tracker")
                    .withDetail("status", "unknown")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}

