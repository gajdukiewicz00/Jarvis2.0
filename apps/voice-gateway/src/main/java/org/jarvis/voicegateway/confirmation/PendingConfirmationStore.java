package org.jarvis.voicegateway.confirmation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, per-user store of pending voice confirmations.
 *
 * <p>Only ONE confirmation is tracked per user at a time (a newer guarded action supersedes an
 * older un-confirmed one). Entries expire after {@code ttlMs} so a stale "yes" said a minute later
 * cannot silently execute a forgotten action — {@link #take(String)} distinguishes an expired
 * entry ({@link TakeStatus#EXPIRED}) from no entry at all ({@link TakeStatus#NONE}) so the handler
 * can say "confirmation expired" versus "nothing to confirm".
 *
 * <p>TTL default 60s, overridable via env {@code JARVIS_VOICE_CONFIRMATION_TTL_MS} (property
 * {@code jarvis.voice.confirmation.ttl-ms}). Thread-safe: backed by a {@link ConcurrentHashMap}.
 */
@Slf4j
@Component
public class PendingConfirmationStore {

    /** Default time-to-live for a pending confirmation (60 seconds). */
    public static final long DEFAULT_TTL_MS = 60_000L;

    @Value("${jarvis.voice.confirmation.ttl-ms:60000}")
    private long ttlMs = DEFAULT_TTL_MS;

    private final Map<String, PendingConfirmation> byUser = new ConcurrentHashMap<>();

    /** Outcome of {@link #take(String)} — a pending was FOUND, had EXPIRED, or there was NONE. */
    public enum TakeStatus {
        FOUND,
        EXPIRED,
        NONE
    }

    /** Result of taking a pending confirmation: a status plus the pending itself when FOUND. */
    public record TakeResult(TakeStatus status, PendingConfirmation pending) {

        public static TakeResult found(PendingConfirmation pending) {
            return new TakeResult(TakeStatus.FOUND, pending);
        }

        public static TakeResult expired() {
            return new TakeResult(TakeStatus.EXPIRED, null);
        }

        public static TakeResult none() {
            return new TakeResult(TakeStatus.NONE, null);
        }

        public boolean isFound() {
            return status == TakeStatus.FOUND;
        }

        public boolean isExpired() {
            return status == TakeStatus.EXPIRED;
        }

        public boolean isNone() {
            return status == TakeStatus.NONE;
        }
    }

    /**
     * Creates and stores a pending confirmation for {@code userId}, replacing any prior pending.
     *
     * @return the stored {@link PendingConfirmation} (never null)
     */
    public PendingConfirmation create(
            String userId,
            String service,
            String intent,
            String tool,
            Map<String, Object> args,
            String originalTranscript,
            String normalizedTranscript) {
        long now = System.currentTimeMillis();
        PendingConfirmation pending = new PendingConfirmation(
                UUID.randomUUID().toString(),
                userId,
                originalTranscript,
                normalizedTranscript,
                service,
                intent,
                tool,
                args,
                now,
                now + Math.max(1L, ttlMs),
                PendingConfirmation.STATUS_PENDING);
        byUser.put(keyFor(userId), pending);
        log.info("pendingConfirmation.created confirmationId={} userId={} intent={} service={} tool={} ttlMs={}",
                pending.confirmationId(), maskUserId(userId), intent, service, tool, ttlMs);
        return pending;
    }

    /**
     * Atomically removes and returns the pending confirmation for {@code userId}.
     *
     * <ul>
     *   <li>{@link TakeStatus#FOUND} — a live (non-expired) pending was removed and returned;</li>
     *   <li>{@link TakeStatus#EXPIRED} — a pending existed but had expired (logged, removed);</li>
     *   <li>{@link TakeStatus#NONE} — there was no pending for this user.</li>
     * </ul>
     */
    public TakeResult take(String userId) {
        PendingConfirmation pending = byUser.remove(keyFor(userId));
        if (pending == null) {
            return TakeResult.none();
        }
        if (pending.isExpired(System.currentTimeMillis())) {
            log.info("pendingConfirmation.expired confirmationId={} userId={} intent={}",
                    pending.confirmationId(), maskUserId(userId), pending.intent());
            return TakeResult.expired();
        }
        return TakeResult.found(pending);
    }

    /**
     * Non-destructive lookup of a live pending confirmation (does not remove it, does not treat an
     * expired entry as present). Primarily for diagnostics/tests.
     */
    public Optional<PendingConfirmation> peek(String userId) {
        PendingConfirmation pending = byUser.get(keyFor(userId));
        if (pending == null || pending.isExpired(System.currentTimeMillis())) {
            return Optional.empty();
        }
        return Optional.of(pending);
    }

    /** True when a live (non-expired) pending confirmation exists for the user. */
    public boolean hasPending(String userId) {
        return peek(userId).isPresent();
    }

    /** Removes any pending confirmation for the user. */
    public void remove(String userId) {
        byUser.remove(keyFor(userId));
    }

    private String keyFor(String userId) {
        return userId == null || userId.isBlank() ? "__anonymous__" : userId;
    }

    private String maskUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return "<none>";
        }
        if (userId.length() <= 2) {
            return "***";
        }
        return userId.charAt(0) + "***" + userId.charAt(userId.length() - 1);
    }
}
