package org.jarvis.common.safety;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared global panic / kill-switch state (EPIC 3 — "stop all running
 * workflows"). When engaged, every action path that consults it refuses to
 * execute or publish — the gateway tool executor, the orchestrator command
 * publisher, and the voice fast-path.
 *
 * <p>In-memory per service; engage/clear are propagated across services by
 * {@code PanicBroadcaster} over a RabbitMQ fanout so a single
 * {@code POST /api/v1/agent/panic} halts the whole stack. A restarted service
 * resets to not-engaged (documented limitation; durable backing is future
 * work).</p>
 */
@Slf4j
public class SystemPanicState {

    private record State(boolean engaged, String actor, String reason, String sinceMillis) {}

    private final AtomicReference<State> state =
            new AtomicReference<>(new State(false, null, null, null));

    public boolean isEngaged() {
        return state.get().engaged();
    }

    /** Engage locally and log. Returns true if state changed. */
    public boolean engage(String actor, String reason, long nowMillis) {
        boolean wasEngaged = state.get().engaged();
        state.set(new State(true,
                actor == null ? "api" : actor,
                reason == null ? "panic engaged" : reason,
                Long.toString(nowMillis)));
        if (!wasEngaged) {
            log.warn("🚨 SYSTEM PANIC ENGAGED actor={} reason={} — all action paths blocked", actor, reason);
        }
        return !wasEngaged;
    }

    /** Clear locally and log. Returns true if state changed. */
    public boolean clear(String actor, long nowMillis) {
        boolean wasEngaged = state.get().engaged();
        state.set(new State(false, actor == null ? "api" : actor, "cleared", Long.toString(nowMillis)));
        if (wasEngaged) {
            log.warn("✅ SYSTEM PANIC CLEARED actor={} — action paths restored", actor);
        }
        return wasEngaged;
    }

    public Map<String, Object> snapshot() {
        State s = state.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("engaged", s.engaged());
        m.put("actor", s.actor());
        m.put("reason", s.reason());
        m.put("sinceMillis", s.sinceMillis());
        return m;
    }
}
