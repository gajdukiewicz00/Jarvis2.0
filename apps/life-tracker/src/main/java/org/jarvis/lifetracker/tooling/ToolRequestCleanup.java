package org.jarvis.lifetracker.tooling;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRequestCleanup {

    private final ToolRequestRepository toolRequestRepository;

    @Value("${jarvis.tooling.retention-days:30}")
    private long retentionDays;

    @Scheduled(cron = "0 30 3 * * *")
    public void purgeOldToolRequests() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        long deleted = toolRequestRepository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} tool request records older than {} days", deleted, retentionDays);
        }
    }
}
