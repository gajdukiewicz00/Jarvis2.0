package org.jarvis.swarm.web;

import jakarta.servlet.http.HttpServletRequest;
import org.jarvis.swarm.role.RoleCatalog;
import org.jarvis.swarm.role.RoleDefinition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Exposes the role catalog so callers can see each role's permission ceiling and defaults. */
@RestController
@RequestMapping("/api/v1/agents/roles")
public class RoleCatalogController {

    private final RoleCatalog catalog;
    private final SwarmFeatureGate gate;

    public RoleCatalogController(RoleCatalog catalog, SwarmFeatureGate gate) {
        this.catalog = catalog;
        this.gate = gate;
    }

    @GetMapping
    public List<RoleDefinition> roles(HttpServletRequest http) {
        gate.ensureEnabled();
        UserContext.requireUserId(http);
        return catalog.all();
    }
}
