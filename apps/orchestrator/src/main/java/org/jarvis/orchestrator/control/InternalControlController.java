package org.jarvis.orchestrator.control;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.safety.SystemPanicState;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Internal control surface so the global panic kill-switch can be propagated to
 * the orchestrator (the api-gateway has no broker; it pushes engage/clear here).
 * SVC_INTERNAL-only — never client-facing.
 */
@Slf4j
@RestController
@RequestMapping("/internal/control")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SVC_INTERNAL')")
public class InternalControlController {

    private final SystemPanicState panicState;

    @PostMapping("/panic")
    public ResponseEntity<Map<String, Object>> setPanic(@RequestBody PanicCommand body) {
        if (body != null && body.engaged()) {
            panicState.engage(body.actor(), body.reason(), System.currentTimeMillis());
        } else {
            panicState.clear(body == null ? "api" : body.actor(), System.currentTimeMillis());
        }
        return ResponseEntity.ok(panicState.snapshot());
    }

    @GetMapping("/panic")
    public ResponseEntity<Map<String, Object>> panicStatus() {
        return ResponseEntity.ok(panicState.snapshot());
    }

    public record PanicCommand(boolean engaged, String actor, String reason) {}
}
