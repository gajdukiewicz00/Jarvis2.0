package org.jarvis.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Phase 3 — fast-intent classification result.
 *
 * <p>{@code source} reflects which mechanism actually produced the answer:
 * {@code router} when the host router model classified, {@code fallback}
 * when the daemon was unreachable and the caller should fall back to
 * deterministic NLP.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntentResponse {

    private String intent;
    private double confidence;
    private String source;
    private String reason;
    private String correlationId;
}
