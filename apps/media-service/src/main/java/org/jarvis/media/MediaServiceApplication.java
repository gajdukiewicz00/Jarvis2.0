package org.jarvis.media;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Media / Russian-dubbing assistant service (EPIC 8).
 *
 * <p>Inspects media files, detects audio/subtitle streams, produces Russian
 * subtitles, and prepares a safe neutral-voice Russian dubbing pipeline. All
 * heavy work runs on an async job executor; HTTP request threads never block on
 * long media jobs. Every external binary call (ffprobe/ffmpeg) goes through a
 * process wrapper with an argument list — never a shell string. {@code
 * @EnableScheduling} backs the periodic workspace-artifact TTL cleanup (see
 * {@code WorkspaceCleanupService}).</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MediaServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MediaServiceApplication.class, args);
    }
}
