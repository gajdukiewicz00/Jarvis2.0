package org.jarvis.voicegateway.voiceloop;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.commands.voice.VoiceSession;
import org.jarvis.commands.voice.VoiceSessionStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 7 — in-memory store of active voice sessions.
 *
 * <p>Pass 1 keeps it local. Phase 8 will project session lifecycle events
 * into Kafka {@code jarvis.voice.events} and Postgres for history.</p>
 *
 * <p>Stale sessions (no transition for {@code session-ttl-seconds}) are
 * marked {@link VoiceSessionStatus#EXPIRED} by the scheduled sweeper so
 * the registry stays bounded.</p>
 */
@Slf4j
@Component
@EnableScheduling
public class VoiceSessionRegistry {

    private final Map<String, VoiceSession> sessions = new ConcurrentHashMap<>();

    @Value("${jarvis.voice.session-ttl-seconds:120}")
    private long ttlSeconds;

    public VoiceSession start(String agentId, String userId) {
        Instant now = Instant.now();
        VoiceSession session = VoiceSession.builder()
                .sessionId(VoiceSession.newSessionId())
                .agentId(agentId)
                .userId(userId)
                .status(VoiceSessionStatus.STARTED)
                .startedAt(now)
                .expiresAt(now.plusSeconds(ttlSeconds))
                .build();
        sessions.put(session.getSessionId(), session);
        log.info("[{}] voice session STARTED agent={} user={}", session.getSessionId(),
                agentId, userId);
        return session;
    }

    public Optional<VoiceSession> get(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public List<VoiceSession> list() {
        return List.copyOf(sessions.values());
    }

    public VoiceSession update(String sessionId, java.util.function.Consumer<VoiceSession> mutator) {
        VoiceSession s = sessions.get(sessionId);
        if (s == null) return null;
        synchronized (s) {
            mutator.accept(s);
            s.setExpiresAt(Instant.now().plusSeconds(ttlSeconds));
        }
        return s;
    }

    /** B1 — mark an active session CANCELLED (barge-in). Kept in the map for status polling. */
    public Optional<VoiceSession> cancel(String sessionId, String reason) {
        VoiceSession s = sessions.get(sessionId);
        if (s == null) {
            return Optional.empty();
        }
        synchronized (s) {
            s.setStatus(VoiceSessionStatus.CANCELLED);
            s.setExpiresAt(Instant.now().plusSeconds(ttlSeconds));
        }
        log.info("[{}] voice session CANCELLED reason={}", sessionId, reason);
        return Optional.of(s);
    }

    public void end(String sessionId) {
        VoiceSession s = sessions.remove(sessionId);
        if (s != null) {
            s.setStatus(VoiceSessionStatus.ENDED);
            s.setEndedAt(Instant.now());
            log.info("[{}] voice session ENDED status={}", sessionId, s.getStatus());
        }
    }

    @Scheduled(fixedDelayString = "${jarvis.voice.session-sweep-ms:30000}")
    public void sweepStale() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, VoiceSession>> it = sessions.entrySet().iterator();
        int swept = 0;
        while (it.hasNext()) {
            var entry = it.next();
            VoiceSession s = entry.getValue();
            if (s.getExpiresAt() != null && s.getExpiresAt().isBefore(now)) {
                s.setStatus(VoiceSessionStatus.EXPIRED);
                s.setEndedAt(now);
                it.remove();
                swept++;
                log.warn("[{}] voice session EXPIRED (no activity > {}s)", s.getSessionId(), ttlSeconds);
            }
        }
        if (swept > 0) {
            log.info("VoiceSessionRegistry swept {} expired session(s); {} active",
                    swept, sessions.size());
        }
    }

    public int size() {
        return sessions.size();
    }
}
