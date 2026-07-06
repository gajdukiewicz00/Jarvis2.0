package org.jarvis.apigateway.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.commands.agent.AgentHeartbeat;
import org.jarvis.commands.agent.AgentIdentity;
import org.jarvis.common.safety.SystemPanicState;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.Map;

/**
 * Phase 6 — backend-side surface of the Native Desktop Agent contract.
 *
 * <ul>
 *   <li>{@code POST /api/v1/agent/register}      — first contact, returns the identity echo.</li>
 *   <li>{@code POST /api/v1/agent/heartbeat}     — periodic snapshot.</li>
 *   <li>{@code POST /api/v1/agent/{id}/kill-switch} — engage / disengage.</li>
 *   <li>{@code GET  /api/v1/agent/{id}}          — current registry entry.</li>
 *   <li>{@code GET  /api/v1/agent}               — list all known agents.</li>
 * </ul>
 *
 * <p>Pass 1 is memory-only and unauthenticated at this controller — the
 * api-gateway's edge security layer (JWT) gates traffic before reaching
 * here. Phase 8 will move persistence to Postgres and add per-agent
 * authorization.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
@EnableScheduling
public class AgentControlController {

    private final AgentRegistry registry;
    private final SystemPanicState panicState;
    private final PanicPropagator panicPropagator;

    /** Engage the global panic kill-switch — halts all action paths (EPIC 3). */
    @PreAuthorize("hasAnyAuthority('ADMIN','OWNER')")
    @PostMapping("/panic")
    public ResponseEntity<Map<String, Object>> engagePanic(@RequestBody(required = false) PanicRequest body) {
        String actor = body == null || body.actor() == null ? "api" : body.actor();
        String reason = body == null || body.reason() == null ? "panic engaged via REST" : body.reason();
        panicState.engage(actor, reason, System.currentTimeMillis());
        panicPropagator.propagate(true, actor, reason);
        return ResponseEntity.ok(panicState.snapshot());
    }

    /** Clear the global panic kill-switch — restores action paths. */
    @PreAuthorize("hasAnyAuthority('ADMIN','OWNER')")
    @PostMapping("/panic/clear")
    public ResponseEntity<Map<String, Object>> clearPanic(@RequestBody(required = false) PanicRequest body) {
        String actor = body == null || body.actor() == null ? "api" : body.actor();
        panicState.clear(actor, System.currentTimeMillis());
        panicPropagator.propagate(false, actor, "cleared");
        return ResponseEntity.ok(panicState.snapshot());
    }

    @GetMapping("/panic")
    public ResponseEntity<Map<String, Object>> panicStatus() {
        return ResponseEntity.ok(panicState.snapshot());
    }

    @PostMapping("/register")
    public ResponseEntity<AgentIdentity> register(@RequestBody AgentIdentity identity) {
        try {
            return ResponseEntity.ok(registry.register(identity));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@RequestBody AgentHeartbeat heartbeat) {
        boolean accepted = registry.recordHeartbeat(heartbeat);
        return accepted ? ResponseEntity.accepted().build()
                : ResponseEntity.badRequest().build();
    }

    @PreAuthorize("hasAnyAuthority('ADMIN','OWNER')")
    @PostMapping("/{agentId}/kill-switch")
    public ResponseEntity<Void> killSwitch(@PathVariable String agentId,
                                           @RequestBody KillSwitchRequest body) {
        boolean ok = body.engaged()
                ? registry.engageKillSwitch(agentId,
                        body.actor() == null ? "api" : body.actor(),
                        body.reason() == null ? "engaged via REST" : body.reason())
                : registry.disengageKillSwitch(agentId,
                        body.actor() == null ? "api" : body.actor());
        return ok ? ResponseEntity.accepted().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/{agentId}")
    public ResponseEntity<AgentRegistry.Entry> get(@PathVariable String agentId) {
        return registry.get(agentId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public Collection<AgentRegistry.Entry> list() {
        return registry.list();
    }

    public record KillSwitchRequest(boolean engaged, String actor, String reason) {}

    public record PanicRequest(String actor, String reason) {}
}
