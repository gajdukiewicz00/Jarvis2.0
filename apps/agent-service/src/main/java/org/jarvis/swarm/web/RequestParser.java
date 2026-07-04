package org.jarvis.swarm.web;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.role.AgentRole;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Validates and parses role/permission names from request bodies (fail-fast on unknown). */
public final class RequestParser {

    private RequestParser() {
    }

    public static Set<ToolPermission> parsePermissions(List<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        EnumSet<ToolPermission> result = EnumSet.noneOf(ToolPermission.class);
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                result.add(ToolPermission.valueOf(name.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown permission: " + name);
            }
        }
        return Set.copyOf(result);
    }

    public static List<AgentRole> parseRoles(List<String> names) {
        List<AgentRole> roles = new ArrayList<>();
        if (names != null) {
            for (String name : names) {
                roles.add(AgentRole.fromText(name));
            }
        }
        return roles;
    }
}
