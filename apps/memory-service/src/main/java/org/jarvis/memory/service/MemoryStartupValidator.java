package org.jarvis.memory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryStartupValidator implements ApplicationRunner {

    private final MemoryDependencyStatusService dependencyStatusService;

    @Value("${memory.startup.fail-fast:true}")
    private boolean failFast;

    @Override
    public void run(ApplicationArguments args) {
        if (!failFast) {
            log.info("memory-service startup dependency validation disabled.");
            return;
        }

        dependencyStatusService.verifyOrThrow();
        log.info("memory-service dependencies validated successfully.");
    }
}
