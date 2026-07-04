package org.jarvis.swarm.role;

import org.jarvis.common.safety.ToolPermission;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoleCatalogTest {

    private final RoleCatalog catalog = new RoleCatalog();

    @Test
    void definesAllSevenRoles() {
        assertThat(catalog.all()).hasSize(7);
        for (AgentRole role : AgentRole.values()) {
            assertThat(catalog.definition(role)).isNotNull();
        }
    }

    @Test
    void financeAndMediaGetNoDangerousPermissions() {
        for (AgentRole role : new AgentRole[]{AgentRole.FINANCE, AgentRole.MEDIA}) {
            RoleDefinition def = catalog.definition(role);
            assertThat(def.allowedPermissions())
                    .doesNotContain(ToolPermission.RUN_SHELL, ToolPermission.WRITE_FILES,
                            ToolPermission.NETWORK_ACCESS);
        }
    }

    @Test
    void coderDoesNotGetShellAndTesterDoesNotGetUnrestrictedWrite() {
        assertThat(catalog.definition(AgentRole.CODER).allowedPermissions())
                .doesNotContain(ToolPermission.RUN_SHELL);
        assertThat(catalog.definition(AgentRole.TESTER).allowedPermissions())
                .doesNotContain(ToolPermission.WRITE_FILES);
    }

    @Test
    void rolePermissionSetsAreDistinct() {
        assertThat(catalog.definition(AgentRole.CODER).allowedPermissions())
                .isNotEqualTo(catalog.definition(AgentRole.SECURITY).allowedPermissions());
        assertThat(catalog.definition(AgentRole.FINANCE).allowedPermissions())
                .contains(ToolPermission.FINANCE_ACCESS);
        assertThat(catalog.definition(AgentRole.MEDIA).allowedPermissions())
                .contains(ToolPermission.MEDIA_ACCESS);
    }

    @Test
    void sandboxRequiredForCodingRolesNotForReadOnlyExternalRoles() {
        assertThat(catalog.definition(AgentRole.CODER).sandboxRequired()).isTrue();
        assertThat(catalog.definition(AgentRole.DOCS).sandboxRequired()).isTrue();
        assertThat(catalog.definition(AgentRole.MEDIA).sandboxRequired()).isFalse();
    }
}
