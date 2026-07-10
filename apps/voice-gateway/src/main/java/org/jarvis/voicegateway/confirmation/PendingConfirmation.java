package org.jarvis.voicegateway.confirmation;

import java.util.Map;

/**
 * A single guarded voice action awaiting an explicit "yes/confirm" from the user.
 *
 * <p>Created when a rule whose action name ends with {@code _CONFIRM} matches and carries a
 * {@code confirmAction} parameter (e.g. the "что на экране" vision rule). It captures everything
 * needed to execute the real action later, when the user confirms, without re-running rule
 * matching: the resolved {@code intent} (what to do), the target {@code service}/{@code tool}
 * (where to route it), and the cleaned {@code args} (the confirm* bookkeeping keys stripped out).
 *
 * <p>Immutable value type. {@link #args} is defensively copied so the stored pending state can
 * never be mutated by a caller after creation.
 */
public record PendingConfirmation(
        String confirmationId,
        String userId,
        String originalTranscript,
        String normalizedTranscript,
        String service,
        String intent,
        String tool,
        Map<String, Object> args,
        long createdAtMs,
        long expiresAtMs,
        String status) {

    public static final String STATUS_PENDING = "PENDING";

    public PendingConfirmation {
        args = args == null ? Map.of() : Map.copyOf(args);
    }

    /** True when {@code nowMs} is at or past this pending confirmation's expiry instant. */
    public boolean isExpired(long nowMs) {
        return nowMs >= expiresAtMs;
    }
}
