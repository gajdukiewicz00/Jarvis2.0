package org.jarvis.swarm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded worker pool for agent tasks. Keeps agent work off request threads; the
 * bounded queue + AbortPolicy means a saturated swarm rejects new tasks (surfaced as
 * 503) rather than growing memory without limit.
 */
@Configuration
public class AsyncConfig {

    public static final String AGENT_TASK_EXECUTOR = "agentTaskExecutor";

    @Bean(name = AGENT_TASK_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService agentTaskExecutor(SwarmProperties props) {
        int pool = Math.max(1, props.queue().workerPoolSize());
        var queue = new LinkedBlockingQueue<Runnable>(Math.max(1, props.queue().capacity()));
        return new ThreadPoolExecutor(
                pool, pool,
                60L, TimeUnit.SECONDS,
                queue,
                new CustomizableThreadFactory("agent-task-"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    public Clock swarmClock() {
        return Clock.systemUTC();
    }
}
