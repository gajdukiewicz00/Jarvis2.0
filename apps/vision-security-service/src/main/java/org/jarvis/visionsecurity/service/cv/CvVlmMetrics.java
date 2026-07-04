package org.jarvis.visionsecurity.service.cv;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Prometheus metrics for local VLM calls. Tags are all low-cardinality
 * (provider id, availability outcome) so the series count stays bounded.
 *
 * <ul>
 *   <li>{@code jarvis_cv_vlm_requests_total{provider,outcome}}</li>
 *   <li>{@code jarvis_cv_vlm_failures_total{provider,reason}}</li>
 *   <li>{@code jarvis_cv_vlm_duration_seconds{provider,outcome}} (Timer)</li>
 * </ul>
 */
@Component
public class CvVlmMetrics {

    static final String METRIC_REQUESTS = "jarvis_cv_vlm_requests_total";
    static final String METRIC_FAILURES = "jarvis_cv_vlm_failures_total";
    static final String METRIC_DURATION = "jarvis_cv_vlm_duration";

    private final MeterRegistry meterRegistry;

    public CvVlmMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** Record one VLM call described by {@code result} under {@code provider}. */
    public void record(String provider, LocalVlmAdapter.VlmResult result) {
        if (result == null) {
            return;
        }
        String p = provider == null || provider.isBlank() ? "unknown" : provider;
        String outcome = result.availability().name().toLowerCase(Locale.ROOT);

        Counter.builder(METRIC_REQUESTS)
                .tags(Tags.of("provider", p, "outcome", outcome))
                .register(meterRegistry)
                .increment();

        if (result.availability() != LocalVlmAdapter.Availability.READY) {
            Counter.builder(METRIC_FAILURES)
                    .tags(Tags.of("provider", p, "reason", outcome))
                    .register(meterRegistry)
                    .increment();
        }

        Timer.builder(METRIC_DURATION)
                .tags(Tags.of("provider", p, "outcome", outcome))
                .publishPercentiles(0.5, 0.95)
                .register(meterRegistry)
                .record(Math.max(0L, result.durationMs()), TimeUnit.MILLISECONDS);
    }
}
