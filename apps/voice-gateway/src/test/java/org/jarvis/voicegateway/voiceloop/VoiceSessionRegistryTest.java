package org.jarvis.voicegateway.voiceloop;

import org.jarvis.commands.voice.VoiceSession;
import org.jarvis.commands.voice.VoiceSessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceSessionRegistryTest {

    private VoiceSessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new VoiceSessionRegistry();
        ReflectionTestUtils.setField(registry, "ttlSeconds", 120L);
    }

    @Test
    void startCreatesSessionWithStartedStatusAndStoresIt() {
        VoiceSession session = registry.start("agent-1", "user-1");

        assertNotNull(session.getSessionId());
        assertTrue(session.getSessionId().startsWith("vs-"));
        assertEquals("agent-1", session.getAgentId());
        assertEquals("user-1", session.getUserId());
        assertEquals(VoiceSessionStatus.STARTED, session.getStatus());
        assertEquals(1, registry.size());
        assertTrue(registry.get(session.getSessionId()).isPresent());
    }

    @Test
    void getReturnsEmptyForUnknownSession() {
        assertTrue(registry.get("does-not-exist").isEmpty());
    }

    @Test
    void listReturnsAllActiveSessions() {
        registry.start("agent-1", "user-1");
        registry.start("agent-2", "user-2");

        List<VoiceSession> all = registry.list();

        assertEquals(2, all.size());
    }

    @Test
    void updateMutatesExistingSessionAndBumpsExpiry() {
        VoiceSession session = registry.start("agent-1", "user-1");
        Instant firstExpiry = session.getExpiresAt();

        VoiceSession updated = registry.update(session.getSessionId(), s -> s.setTranscript("hello"));

        assertNotNull(updated);
        assertEquals("hello", updated.getTranscript());
        assertFalse(updated.getExpiresAt().isBefore(firstExpiry));
    }

    @Test
    void updateReturnsNullForUnknownSession() {
        VoiceSession updated = registry.update("missing", s -> s.setTranscript("x"));
        assertNull(updated);
    }

    @Test
    void guardedUpdateAppliesMutatorWhenGuardPasses() {
        VoiceSession session = registry.start("agent-1", "user-1");

        VoiceSession updated = registry.update(session.getSessionId(),
                s -> s.getStatus() != VoiceSessionStatus.CANCELLED,
                s -> s.setStatus(VoiceSessionStatus.COMPLETED));

        assertEquals(VoiceSessionStatus.COMPLETED, updated.getStatus());
    }

    @Test
    void guardedUpdateSkipsMutatorWhenGuardFails() {
        VoiceSession session = registry.start("agent-1", "user-1");
        registry.cancel(session.getSessionId(), "barge-in");

        VoiceSession updated = registry.update(session.getSessionId(),
                s -> s.getStatus() != VoiceSessionStatus.CANCELLED,
                s -> s.setStatus(VoiceSessionStatus.COMPLETED));

        assertNotNull(updated);
        assertEquals(VoiceSessionStatus.CANCELLED, updated.getStatus());
    }

    @Test
    void guardedUpdateReturnsNullForUnknownSession() {
        VoiceSession updated = registry.update("missing", s -> true, s -> s.setTranscript("x"));
        assertNull(updated);
    }

    @Test
    void cancelMarksSessionCancelledAndReturnsIt() {
        VoiceSession session = registry.start("agent-1", "user-1");

        Optional<VoiceSession> cancelled = registry.cancel(session.getSessionId(), "user_cancel");

        assertTrue(cancelled.isPresent());
        assertEquals(VoiceSessionStatus.CANCELLED, cancelled.get().getStatus());
    }

    @Test
    void cancelReturnsEmptyForUnknownSession() {
        assertTrue(registry.cancel("missing", "reason").isEmpty());
    }

    @Test
    void endRemovesSessionAndMarksEnded() {
        VoiceSession session = registry.start("agent-1", "user-1");

        registry.end(session.getSessionId());

        assertTrue(registry.get(session.getSessionId()).isEmpty());
        assertEquals(0, registry.size());
    }

    @Test
    void endOnUnknownSessionIsNoop() {
        registry.end("missing");
        assertEquals(0, registry.size());
    }

    @Test
    void sweepStaleRemovesExpiredSessionsOnly() {
        VoiceSession fresh = registry.start("agent-1", "user-1");
        VoiceSession stale = registry.start("agent-2", "user-2");
        // Force the stale session's expiry into the past directly (registry.update()
        // always re-bumps expiresAt to now+ttl after the mutator runs, so we mutate
        // the stored instance in place instead of going through update()).
        stale.setExpiresAt(Instant.now().minusSeconds(5));

        registry.sweepStale();

        assertTrue(registry.get(fresh.getSessionId()).isPresent());
        assertTrue(registry.get(stale.getSessionId()).isEmpty());
        assertEquals(1, registry.size());
    }

    @Test
    void sweepStaleWithNoExpiredSessionsLeavesRegistryUnchanged() {
        registry.start("agent-1", "user-1");

        registry.sweepStale();

        assertEquals(1, registry.size());
    }

    @Test
    void sizeReflectsActiveSessionCount() {
        assertEquals(0, registry.size());
        registry.start("agent-1", "user-1");
        assertEquals(1, registry.size());
    }
}
