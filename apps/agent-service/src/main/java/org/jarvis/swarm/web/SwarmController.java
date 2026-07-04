package org.jarvis.swarm.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.run.CombinedReport;
import org.jarvis.swarm.run.SwarmCoordinator;
import org.jarvis.swarm.run.SwarmRun;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/** Swarm run endpoints: start a multi-role run and fetch its combined report. */
@RestController
@RequestMapping("/api/v1/agents/swarm")
public class SwarmController {

    private final SwarmCoordinator coordinator;
    private final SwarmFeatureGate gate;

    public SwarmController(SwarmCoordinator coordinator, SwarmFeatureGate gate) {
        this.coordinator = coordinator;
        this.gate = gate;
    }

    @PostMapping
    public ResponseEntity<?> start(@Valid @RequestBody CreateSwarmRequest request, HttpServletRequest http) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(http);
        List<AgentRole> roles = RequestParser.parseRoles(request.roles());
        Set<ToolPermission> permissions = RequestParser.parsePermissions(request.permissions());
        SwarmRun run = coordinator.submit(userId, request.goal(), roles, permissions, request.dryRun());
        if (request.awaitCompletion()) {
            CombinedReport report = coordinator.awaitAndReport(userId, run.swarmId());
            return ResponseEntity.ok(report);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(run);
    }

    @GetMapping("/{id}")
    public CombinedReport report(@PathVariable String id, HttpServletRequest http) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(http);
        return coordinator.report(userId, id);
    }
}
