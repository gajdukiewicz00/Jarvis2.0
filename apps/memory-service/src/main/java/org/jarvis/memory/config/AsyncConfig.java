package org.jarvis.memory.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enable async processing for fire-and-forget operations
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}



