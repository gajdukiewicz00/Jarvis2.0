package org.jarvis.commands.voice;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Phase 7 — formatted spoken response back to the user.
 *
 * <p>{@code spokenText} is the exact text passed to TTS. {@code displayText}
 * is the same text but optionally enriched for the desktop panel (no
 * Iron-Man-Jarvis style required). {@code level} drives UI tinting and
 * audit severity.</p>
 */
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VoiceFeedback {

    public enum Level { INFO, WARN, ERROR }

    private String code;          // e.g. "SUCCESS", "DENIED", "TIMEOUT"
    private Level level;
    private String spokenText;
    private String displayText;
}
