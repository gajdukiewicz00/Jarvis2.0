package org.jarvis.commands;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Phase 4 — small builder facade. Centralises defaults (id minting, status,
 * timestamps) so publishers can't accidentally create a command with a
 * missing required field.
 */
public final class CommandFactory {

    private CommandFactory() {}

    /**
     * Build a fresh command ready to publish.
     *
     * @param userId        end-user subject id; must not be null
     * @param source        origin (voice, desktop UI, mobile, ...)
     * @param intent        normalized intent name
     * @param riskLevel     risk classification (Phase 5 confirmation gate)
     * @param payload       executor-specific arguments
     * @param ttl           how long the command remains valid (also queue TTL)
     * @param correlationId optional correlation id; auto-minted if null
     * @return envelope with status=CREATED, createdAt=now, fresh commandId
     */
    public static CommandEnvelope create(
            String userId,
            CommandSource source,
            String intent,
            RiskLevel riskLevel,
            Map<String, Object> payload,
            Duration ttl,
            String correlationId) {

        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
        if (intent == null || intent.isBlank()) {
            throw new IllegalArgumentException("intent is required");
        }
        if (riskLevel == null) {
            throw new IllegalArgumentException("riskLevel is required");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be a positive Duration");
        }

        Instant now = Instant.now();
        boolean confirmRequired = riskLevel.ordinal() >= RiskLevel.MEDIUM.ordinal();

        return CommandEnvelope.builder()
                .commandId(CommandEnvelope.newCommandId())
                .correlationId(correlationId == null ? CommandEnvelope.newCommandId() : correlationId)
                .userId(userId)
                .source(source)
                .intent(intent)
                .riskLevel(riskLevel)
                .requiresConfirmation(confirmRequired)
                .createdAt(now)
                .expiresAt(now.plus(ttl))
                .status(CommandStatus.CREATED)
                .payload(payload)
                .build();
    }
}
