package org.jarvis.swarm.role;

import org.jarvis.common.safety.ToolPermission;

import java.util.Set;

/**
 * Static definition of a role: the MAXIMUM permission set it may ever be granted, plus
 * its operational defaults. A task only actually receives a permission if the role
 * allows it AND the user requested it (and, for dangerous permissions, the system
 * policy backstop allows it). So {@code allowedPermissions} is a ceiling, not a grant.
 *
 * @param role              the role
 * @param displayName       human label
 * @param allowedPermissions ceiling of permissions this role may use
 * @param defaultTimeoutSeconds per-task timeout
 * @param maxRetries        retry budget on failure
 * @param sandboxRequired   whether the role must run inside a sandbox
 * @param description       what the role does
 */
public record RoleDefinition(
        AgentRole role,
        String displayName,
        Set<ToolPermission> allowedPermissions,
        int defaultTimeoutSeconds,
        int maxRetries,
        boolean sandboxRequired,
        String description) {

    public RoleDefinition {
        allowedPermissions = Set.copyOf(allowedPermissions);
    }

    public boolean allows(ToolPermission permission) {
        return allowedPermissions.contains(permission);
    }
}
