package org.jarvis.swarm.permission;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.role.RoleCatalog;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPermissionResolverTest {

    private final AgentPermissionResolver resolver = new AgentPermissionResolver(new RoleCatalog());

    @Test
    void grantsOnlyTheIntersectionOfRoleAndUserRequest() {
        // TESTER allows READ_FILES + RUN_SHELL; user requests RUN_SHELL + FINANCE_ACCESS
        Set<ToolPermission> granted = resolver.resolveGrants(AgentRole.TESTER,
                Set.of(ToolPermission.RUN_SHELL, ToolPermission.FINANCE_ACCESS));
        assertThat(granted).containsExactly(ToolPermission.RUN_SHELL);
    }

    @Test
    void reportsPermissionsTheRoleRefuses() {
        Set<ToolPermission> denied = resolver.deniedByRole(AgentRole.CODER,
                Set.of(ToolPermission.RUN_SHELL, ToolPermission.WRITE_FILES));
        assertThat(denied).contains(ToolPermission.RUN_SHELL); // CODER forbids shell
        assertThat(denied).doesNotContain(ToolPermission.WRITE_FILES); // CODER allows write
    }

    @Test
    void emptyRequestGrantsNothing() {
        assertThat(resolver.resolveGrants(AgentRole.CODER, Set.of())).isEmpty();
    }
}
