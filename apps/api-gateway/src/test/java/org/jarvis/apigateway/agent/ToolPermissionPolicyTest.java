package org.jarvis.apigateway.agent;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.common.safety.ToolPermissionPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolPermissionPolicyTest {

    @Test
    void defaultPolicyAllowsProductivityToolsAndDeniesFinance() {
        ToolPermissionPolicy policy = new ToolPermissionPolicy("");

        assertThat(policy.isAllowed("create_todo")).isTrue();      // PLANNER_ACCESS granted
        assertThat(policy.isAllowed("create_event")).isTrue();     // CALENDAR_ACCESS granted
        assertThat(policy.isAllowed("memory_search")).isTrue();    // MEMORY_ACCESS granted
        assertThat(policy.isAllowed("finance_summary")).isFalse(); // FINANCE_ACCESS NOT granted by default
        assertThat(policy.missingFor("finance_summary")).contains(ToolPermission.FINANCE_ACCESS);
    }

    @Test
    void unknownToolRequiresNothingAndIsAllowed() {
        ToolPermissionPolicy policy = new ToolPermissionPolicy("");
        assertThat(policy.requiredFor("totally_unknown_tool")).isEmpty();
        assertThat(policy.isAllowed("totally_unknown_tool")).isTrue();
    }

    @Test
    void explicitGrantEnablesFinance() {
        ToolPermissionPolicy policy = new ToolPermissionPolicy("PLANNER_ACCESS, FINANCE_ACCESS");
        assertThat(policy.isAllowed("finance_summary")).isTrue();
        assertThat(policy.isAllowed("create_todo")).isTrue();
        // calendar not in the explicit grant list -> denied
        assertThat(policy.isAllowed("create_event")).isFalse();
    }

    @Test
    void deniesGuardedIntentsWhenNotGranted() {
        ToolPermissionPolicy restrictive = new ToolPermissionPolicy("PLANNER_ACCESS");
        assertThat(restrictive.isIntentAllowed("finance_add_expense")).isFalse();
        assertThat(restrictive.isIntentAllowed("run_shell_command")).isFalse();
        assertThat(restrictive.isIntentAllowed("write_file_note")).isFalse();
        assertThat(restrictive.isIntentAllowed("media_dub_video")).isFalse();
        assertThat(restrictive.isIntentAllowed("smart_home_action")).isFalse();
        assertThat(restrictive.isIntentAllowed("volume_down")).isFalse(); // PC_CONTROL not granted

        assertThat(restrictive.missingForIntent("finance_add_expense")).contains(ToolPermission.FINANCE_ACCESS);
        assertThat(restrictive.missingForIntent("run_shell_command")).contains(ToolPermission.RUN_SHELL);

        ToolPermissionPolicy pc = new ToolPermissionPolicy("PC_CONTROL");
        assertThat(pc.isIntentAllowed("volume_down")).isTrue();
    }
}
