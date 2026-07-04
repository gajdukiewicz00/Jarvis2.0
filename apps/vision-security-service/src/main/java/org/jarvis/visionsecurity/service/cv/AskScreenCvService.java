package org.jarvis.visionsecurity.service.cv;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.AskScreenResult;
import org.jarvis.visionsecurity.model.ScreenContextResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Orchestrates: screenshot → screen-context → local VLM question.
 * <ul>
 *   <li>If VLM is not configured, returns {@code success=true} (screen
 *       context still works) with {@code vlm.availability=NOT_CONFIGURED}
 *       and a {@code null} answer. No fake summary.</li>
 *   <li>If VLM is configured but unreachable, returns {@code success=true}
 *       with {@code vlm.availability=UNAVAILABLE} and the backend error
 *       message; the screen-context payload is still useful.</li>
 *   <li>If VLM responds, returns the model's answer verbatim.</li>
 * </ul>
 */
@Slf4j
@Service
public class AskScreenCvService {

    private final ScreenContextCvService screenContextCvService;
    private final LocalVlmAdapter vlmAdapter;
    private final VisionSecurityProperties properties;
    private final CvVlmMetrics vlmMetrics;

    @Autowired
    public AskScreenCvService(ScreenContextCvService screenContextCvService,
                              LocalVlmAdapter vlmAdapter,
                              VisionSecurityProperties properties,
                              CvVlmMetrics vlmMetrics) {
        this.screenContextCvService = screenContextCvService;
        this.vlmAdapter = vlmAdapter;
        this.properties = properties;
        this.vlmMetrics = vlmMetrics;
    }

    /** Test-friendly constructor: metrics go to a throwaway registry. */
    public AskScreenCvService(ScreenContextCvService screenContextCvService,
                              LocalVlmAdapter vlmAdapter,
                              VisionSecurityProperties properties) {
        this(screenContextCvService, vlmAdapter, properties,
                new CvVlmMetrics(new SimpleMeterRegistry()));
    }

    public AskScreenResult ask(String userId, String question, Path explicitTarget) {
        long startNs = System.nanoTime();
        String trimmedQuestion = question == null ? "" : question.trim();
        log.info("ask-screen started user={} adapter={} model={} questionChars={}",
                userId, vlmAdapter.id(), vlmAdapter.model(), trimmedQuestion.length());

        ScreenContextResult ctx = screenContextCvService.capture(userId, explicitTarget);
        if (!ctx.success()) {
            long durationMs = durationMs(startNs);
            log.warn("ask-screen aborted user={} reason=screen_context_failed durationMs={}",
                    userId, durationMs);
            return new AskScreenResult(
                    trimmedQuestion, null, ctx,
                    new AskScreenResult.VlmInfo(
                            vlmAdapter.id(), vlmAdapter.model(),
                            LocalVlmAdapter.Availability.UNAVAILABLE.name(),
                            0L, "screen-context capture failed"),
                    Instant.now(), durationMs, false, ctx.error());
        }

        Path imagePath = ctx.screenshotPath() == null ? null : Path.of(ctx.screenshotPath());
        LocalVlmAdapter.VlmResult vlmResult = vlmAdapter.answer(trimmedQuestion, imagePath, ctx.analysis());
        vlmMetrics.record(providerLabel(), vlmResult);
        long durationMs = durationMs(startNs);
        String answer = vlmResult.availability() == LocalVlmAdapter.Availability.READY
                ? vlmResult.summary()
                : null;
        AskScreenResult.VlmInfo vlmInfo = new AskScreenResult.VlmInfo(
                providerLabel(),
                vlmAdapter.model(),
                vlmResult.availability().name(),
                vlmResult.durationMs(),
                vlmResult.error());

        log.info("ask-screen finished user={} adapter={} availability={} answerChars={} totalMs={} vlmMs={}",
                userId, vlmAdapter.id(), vlmResult.availability(),
                answer == null ? 0 : answer.length(),
                durationMs, vlmResult.durationMs());

        return new AskScreenResult(
                trimmedQuestion, answer, ctx, vlmInfo,
                Instant.now(), durationMs,
                true, null);
    }

    private String providerLabel() {
        // Prefer the configured provider string (e.g. "ollama") for the
        // wire payload; fall back to the adapter id when missing.
        String provider = properties.getCv().getVlm().getProvider();
        return provider == null || provider.isBlank() ? vlmAdapter.id() : provider;
    }

    private static long durationMs(long startNs) {
        return Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
    }
}
