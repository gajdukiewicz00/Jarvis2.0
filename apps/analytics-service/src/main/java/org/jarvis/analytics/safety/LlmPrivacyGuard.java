package org.jarvis.analytics.safety;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.analytics.dto.ExpenseDTO;
import org.jarvis.analytics.dto.WellnessLogDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

/**
 * Privacy guard for the NL-analytics LLM hand-off. Mirrors the capability
 * contract of {@code org.jarvis.llm.client.LlmProvider} ({@code isLocal()} /
 * {@code allowsSensitiveData()}, used there for B2 memory-privacy
 * enforcement) — jarvis-common is off-limits for this module so the
 * contract is replicated locally rather than shared.
 *
 * <p>analytics-service talks to llm-service over plain HTTP and has no
 * visibility into which concrete backend answers on the other side
 * (on-device llama.cpp vs. an external API provider is llm-service's own
 * runtime choice). Given that opacity, this guard is conservative by
 * default: unless {@code analytics.llm.provider.local} (or the explicit
 * override {@code analytics.llm.provider.allow-sensitive-data}) is set,
 * the downstream endpoint is treated as NOT cleared to receive raw
 * finance/health values — only pre-aggregated/derived text summaries may
 * be sent. Raw {@link ExpenseDTO} / {@link WellnessLogDTO} records (or
 * collections/maps containing them) are always blocked from the external
 * path regardless of what a caller passes in.</p>
 */
@Slf4j
@Component
public class LlmPrivacyGuard {

    private final boolean localProvider;
    private final boolean allowSensitiveOverride;

    public LlmPrivacyGuard(
            @Value("${analytics.llm.provider.local:false}") boolean localProvider,
            @Value("${analytics.llm.provider.allow-sensitive-data:false}") boolean allowSensitiveOverride) {
        this.localProvider = localProvider;
        this.allowSensitiveOverride = allowSensitiveOverride;
    }

    /** Mirrors {@code LlmProvider#isLocal()}: true only when explicitly configured as an on-device backend. */
    public boolean isLocal() {
        return localProvider;
    }

    /**
     * Mirrors {@code LlmProvider#allowsSensitiveData()}: defaults to {@link #isLocal()},
     * with an explicit config override for operators who know their llm-service deployment
     * is trustworthy for sensitive data even though it isn't strictly on-device.
     */
    public boolean allowsSensitiveData() {
        return localProvider || allowSensitiveOverride;
    }

    /**
     * Enforces that raw finance/health records never reach a provider not cleared for
     * sensitive data. Aggregates, derived summaries, and plain strings always pass.
     *
     * @throws SensitiveDataBlockedException if {@code payload} contains a raw
     *         {@link ExpenseDTO} / {@link WellnessLogDTO} (directly, or nested inside a
     *         {@link Collection}/{@link Map}) and the provider isn't cleared for it
     */
    public void assertSafeForExternalLlm(Object payload) {
        if (allowsSensitiveData()) {
            return;
        }
        if (containsRawSensitiveData(payload)) {
            log.warn("LlmPrivacyGuard blocked raw finance/health payload from reaching a non-local LLM provider");
            throw new SensitiveDataBlockedException(
                    "Raw finance/health data cannot be sent to an external LLM provider; send aggregates only.");
        }
    }

    private boolean containsRawSensitiveData(Object payload) {
        if (payload instanceof ExpenseDTO || payload instanceof WellnessLogDTO) {
            return true;
        }
        if (payload instanceof Collection<?> collection) {
            return collection.stream().anyMatch(this::containsRawSensitiveData);
        }
        if (payload instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(this::containsRawSensitiveData);
        }
        return false;
    }

    /** Thrown when raw sensitive data would otherwise be sent to a provider not cleared for it. */
    public static class SensitiveDataBlockedException extends RuntimeException {
        public SensitiveDataBlockedException(String message) {
            super(message);
        }
    }
}
