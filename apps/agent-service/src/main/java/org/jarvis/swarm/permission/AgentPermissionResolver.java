package org.jarvis.swarm.permission;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.role.RoleCatalog;
import org.jarvis.swarm.role.RoleDefinition;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Resolves the effective permission grant for a task: the intersection of what the role
 * may use (its ceiling) and what the user explicitly requested. A permission the user
 * requests but the role forbids is NOT granted (and surfaced as denied-by-role).
 */
@Component
public class AgentPermissionResolver {

    private final RoleCatalog catalog;

    public AgentPermissionResolver(RoleCatalog catalog) {
        this.catalog = catalog;
    }

    /** Effective grant = role-allowed ∩ user-requested. */
    public Set<ToolPermission> resolveGrants(AgentRole role, Set<ToolPermission> requested) {
        RoleDefinition def = catalog.definition(role);
        EnumSet<ToolPermission> granted = EnumSet.noneOf(ToolPermission.class);
        if (requested != null) {
            for (ToolPermission p : requested) {
                if (def.allows(p)) {
                    granted.add(p);
                }
            }
        }
        return Set.copyOf(granted);
    }

    /** Permissions the user requested that the role refuses to grant (for honest reporting). */
    public Set<ToolPermission> deniedByRole(AgentRole role, Set<ToolPermission> requested) {
        RoleDefinition def = catalog.definition(role);
        EnumSet<ToolPermission> denied = EnumSet.noneOf(ToolPermission.class);
        if (requested != null) {
            for (ToolPermission p : requested) {
                if (!def.allows(p)) {
                    denied.add(p);
                }
            }
        }
        return Set.copyOf(denied);
    }
}
