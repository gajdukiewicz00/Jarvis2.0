package org.jarvis.swarm.permission;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.support.SwarmTestFactory;
import org.jarvis.swarm.task.AgentTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentActionGuardTest {

    @TempDir
    Path tmp;

    private AgentTask task(AgentRole role, Set<ToolPermission> granted) {
        return SwarmTestFactory.task(role, "goal", granted, granted, false);
    }

    @Test
    void runShellDeniedWhenNotGrantedByRoleAndUser() {
        var engine = SwarmTestFactory.engine(tmp, "RUN_SHELL,READ_FILES");
        AgentTask task = task(AgentRole.TESTER, Set.of(ToolPermission.READ_FILES)); // RUN_SHELL not granted
        assertThatThrownBy(() -> engine.guard().ensurePermission(task, ToolPermission.RUN_SHELL))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("role+user");
    }

    @Test
    void runShellDeniedBySystemPolicyEvenWhenRoleAndUserGrantIt() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES"); // system policy does NOT grant RUN_SHELL
        AgentTask task = task(AgentRole.TESTER, Set.of(ToolPermission.RUN_SHELL));
        assertThatThrownBy(() -> engine.guard().ensurePermission(task, ToolPermission.RUN_SHELL))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("system policy");
    }

    @Test
    void writeFilesRequiresSystemBackstop() {
        var denied = SwarmTestFactory.engine(tmp, "READ_FILES");
        AgentTask task = task(AgentRole.CODER, Set.of(ToolPermission.WRITE_FILES));
        assertThatThrownBy(() -> denied.guard().ensurePermission(task, ToolPermission.WRITE_FILES))
                .isInstanceOf(PermissionDeniedException.class);

        var allowed = SwarmTestFactory.engine(tmp, "WRITE_FILES,READ_FILES");
        // should not throw
        allowed.guard().ensurePermission(task, ToolPermission.WRITE_FILES);
    }

    @Test
    void financeAndMediaGatedByRoleUserGrantOnlyNotSystemPolicy() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES"); // no FINANCE/MEDIA in system policy
        AgentTask granted = task(AgentRole.FINANCE, Set.of(ToolPermission.FINANCE_ACCESS));
        engine.guard().ensurePermission(granted, ToolPermission.FINANCE_ACCESS); // allowed (user+role grant)

        AgentTask notGranted = task(AgentRole.FINANCE, Set.of());
        assertThatThrownBy(() -> engine.guard().ensurePermission(notGranted, ToolPermission.FINANCE_ACCESS))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void panicBlocksEverything() {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES,FINANCE_ACCESS");
        engine.panic().engage("test", "drill", 1L);
        AgentTask task = task(AgentRole.FINANCE, Set.of(ToolPermission.FINANCE_ACCESS));
        assertThatThrownBy(() -> engine.guard().ensurePermission(task, ToolPermission.FINANCE_ACCESS))
                .isInstanceOf(PanicEngagedException.class);
        assertThatThrownBy(() -> engine.guard().ensureNoPanic(task))
                .isInstanceOf(PanicEngagedException.class);
        assertThat(engine.guard().isPermitted(task, ToolPermission.FINANCE_ACCESS)).isFalse();
    }
}
