package org.jarvis.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Strongly-typed, immutable configuration for the media service (prefix {@code media}).
 * Provider modes default to {@code mock} so the service is fully functional without any
 * native binary (the container image ships no ffmpeg/ffprobe).
 */
@ConfigurationProperties(prefix = "media")
public record MediaProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue Workspace workspace,
        @DefaultValue Executor executor,
        @DefaultValue Ffprobe ffprobe,
        @DefaultValue Ffmpeg ffmpeg,
        @DefaultValue Asr asr,
        @DefaultValue Translation translation,
        @DefaultValue Tts tts,
        @DefaultValue Subtitle subtitle) {

    public record Workspace(
            @DefaultValue("/tmp/jarvis-media") String dir,
            @DefaultValue("") String inputRoots) {}

    public record Executor(
            @DefaultValue("2") int poolSize,
            @DefaultValue("32") int queueCapacity) {}

    public record Ffprobe(
            @DefaultValue("mock") String mode,
            @DefaultValue("ffprobe") String binary,
            @DefaultValue("30") int timeoutSeconds) {}

    public record Ffmpeg(
            @DefaultValue("mock") String mode,
            @DefaultValue("ffmpeg") String binary,
            @DefaultValue("600") int timeoutSeconds) {}

    public record Asr(
            @DefaultValue("mock") String mode,
            @DefaultValue("whisper-cli") String binary,
            @DefaultValue("") String modelPath,
            @DefaultValue("120") int timeoutSeconds) {}

    public record Translation(
            @DefaultValue("mock") String mode,
            @DefaultValue("http://llm-service:8091") String llmServiceUrl) {}

    public record Tts(
            @DefaultValue("mock") String mode,
            @DefaultValue("false") boolean allowUserVoiceProfile) {}

    public record Subtitle(
            @DefaultValue("7") int maxSegmentSeconds,
            @DefaultValue("0.5") double minConfidence) {}
}
