package org.jarvis.media.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Dedicated bounded executor for media jobs. Keeping media work off the request
 * threads guarantees a slow ffmpeg run can never starve HTTP handling. The bounded
 * queue + AbortPolicy means a saturated service rejects new jobs (surfaced as 503)
 * rather than growing memory unboundedly.
 */
@Configuration
public class AsyncConfig {

    public static final String MEDIA_JOB_EXECUTOR = "mediaJobExecutor";

    @Bean(name = MEDIA_JOB_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService mediaJobExecutor(MediaProperties props) {
        int pool = Math.max(1, props.executor().poolSize());
        var queue = new LinkedBlockingQueue<Runnable>(Math.max(1, props.executor().queueCapacity()));
        return new ThreadPoolExecutor(
                pool, pool,
                60L, TimeUnit.SECONDS,
                queue,
                new CustomizableThreadFactory("media-job-"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    public Clock mediaClock() {
        return Clock.systemUTC();
    }
}
