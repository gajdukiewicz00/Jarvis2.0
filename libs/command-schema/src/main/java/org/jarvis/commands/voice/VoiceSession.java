package org.jarvis.commands.voice;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7 — state of a single voice interaction.
 *
 * <p>Created when the wake-word fires and the desktop agent posts to
 * {@code POST /api/v1/voice/sessions}. Updated as the utterance is
 * transcribed, classified, dispatched, and either completed, denied,
 * timed out, or aborted.</p>
 */
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VoiceSession {

    private String sessionId;
    private String agentId;
    private String userId;
    private VoiceSessionStatus status;
    private Instant startedAt;
    private Instant endedAt;
    private Instant expiresAt;

    private String transcript;
    private String intent;
    private double intentConfidence;
    private String intentSource;       // "router" | "regex" | "manual"

    private String commandId;
    private String correlationId;

    private String replyText;
    private String replyAudioUrl;      // Phase 7 ships text; audio URL is optional Pass 2

    public static String newSessionId() {
        return "vs-" + UUID.randomUUID();
    }
}
