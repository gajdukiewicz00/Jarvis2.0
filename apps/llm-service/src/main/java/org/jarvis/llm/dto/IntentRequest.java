package org.jarvis.llm.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Phase 3 — request to the fast-intent router model.
 *
 * <p>Used by {@code nlp-service} to ask the host router model to classify a
 * user utterance into one of the supplied candidate intents.</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class IntentRequest {

    @NotBlank
    private String text;

    /** Optional explicit candidate set. Empty/null = let the model pick freely. */
    private List<String> candidates;

    /** Optional language hint, e.g. "ru" / "en". */
    private String language;

    /** Free-form correlation/trace id passed through to logs and audit. */
    private String correlationId;
}
