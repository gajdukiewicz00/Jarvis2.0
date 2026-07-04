package org.jarvis.swarm.permission;

import org.jarvis.common.safety.ToolPermission;

/**
 * Thrown when an agent attempts a permissioned action it was not granted. Mapped to
 * HTTP 403. The message names the permission and the reason (role, user, or system
 * backstop) so denials are auditable.
 */
public class PermissionDeniedException extends RuntimeException {
    public PermissionDeniedException(ToolPermission permission, String reason) {
        super("Permission denied: " + permission + " (" + reason + ")");
    }
}
