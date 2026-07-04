package org.jarvis.swarm.role;

import org.jarvis.common.safety.ToolPermission;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The role catalog. Each role has a DISTINCT permission ceiling. Safety invariants
 * enforced here by construction:
 * <ul>
 *   <li>FINANCE and MEDIA never get RUN_SHELL / WRITE_FILES / NETWORK_ACCESS.</li>
 *   <li>CODER never gets RUN_SHELL; its WRITE_FILES is sandbox-confined.</li>
 *   <li>TESTER may use RUN_SHELL, but only ever for a sandbox-scoped command and only
 *       when the user grants it AND the system policy backstop allows it.</li>
 *   <li>RESEARCH may use NETWORK_ACCESS only when granted; otherwise it stays offline.</li>
 * </ul>
 */
@Component
public class RoleCatalog {

    private final Map<AgentRole, RoleDefinition> definitions = new EnumMap<>(AgentRole.class);

    public RoleCatalog() {
        define(AgentRole.CODER, "Coder",
                Set.of(ToolPermission.READ_FILES, ToolPermission.WRITE_FILES),
                180, 1, true,
                "Plans implementation and proposes file changes; writes only inside its sandbox.");
        define(AgentRole.TESTER, "Tester",
                Set.of(ToolPermission.READ_FILES, ToolPermission.RUN_SHELL),
                240, 1, true,
                "Proposes a test command; runs it only if RUN_SHELL is granted, capturing output safely.");
        define(AgentRole.RESEARCH, "Research",
                Set.of(ToolPermission.READ_FILES, ToolPermission.NETWORK_ACCESS),
                120, 1, true,
                "Builds a research plan and structured notes; no internet unless NETWORK_ACCESS is granted.");
        define(AgentRole.DOCS, "Docs",
                Set.of(ToolPermission.READ_FILES, ToolPermission.WRITE_FILES),
                120, 1, true,
                "Generates or updates documentation inside its sandbox; no shell required.");
        define(AgentRole.SECURITY, "Security",
                Set.of(ToolPermission.READ_FILES),
                120, 1, true,
                "Runs a safety checklist and scans for obvious risky patterns without exposing secrets.");
        define(AgentRole.MEDIA, "Media",
                Set.of(ToolPermission.READ_FILES, ToolPermission.MEDIA_ACCESS),
                120, 1, false,
                "Prepares media-service job requests; only acts if MEDIA_ACCESS is granted.");
        define(AgentRole.FINANCE, "Finance",
                Set.of(ToolPermission.READ_FILES, ToolPermission.FINANCE_ACCESS),
                120, 1, false,
                "Analyzes finance summaries read-only if FINANCE_ACCESS is granted; never creates transactions.");
    }

    private void define(AgentRole role, String name, Set<ToolPermission> perms,
                        int timeout, int retries, boolean sandbox, String desc) {
        definitions.put(role, new RoleDefinition(role, name, perms, timeout, retries, sandbox, desc));
    }

    public RoleDefinition definition(AgentRole role) {
        RoleDefinition def = definitions.get(role);
        if (def == null) {
            throw new IllegalArgumentException("No definition for role: " + role);
        }
        return def;
    }

    public List<RoleDefinition> all() {
        return List.copyOf(definitions.values());
    }
}
