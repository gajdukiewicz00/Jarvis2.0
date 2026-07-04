package org.jarvis.common.safety;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPermissionPolicyTest {

    @Test
    void blankCsvFallsBackToConservativeDefaultGrants() {
        ToolPermissionPolicy policy = new ToolPermissionPolicy(null);

        assertTrue(policy.granted().contains(ToolPermission.PLANNER_ACCESS));
        assertTrue(policy.granted().contains(ToolPermission.CALENDAR_ACCESS));
        assertTrue(policy.granted().contains(ToolPermission.MEMORY_ACCESS));
        assertFalse(policy.granted().contains(ToolPermission.FINANCE_ACCESS));
        assertFalse(policy.granted().contains(ToolPermission.RUN_SHELL));
        assertFalse(policy.granted().contains(ToolPermission.WRITE_FILES));

        ToolPermissionPolicy blankPolicy = new ToolPermissionPolicy("   ");
        assertEquals(policy.granted(), blankPolicy.granted());
    }

    @Test
    void csvParsesTrimsCaseAndIgnoresUnknownTokens() {
        ToolPermissionPolicy policy = new ToolPermissionPolicy(" finance_access, run_shell ,bogus_permission,,MEDIA_ACCESS");

        assertEquals(Set.of(ToolPermission.FINANCE_ACCESS, ToolPermission.RUN_SHELL, ToolPermission.MEDIA_ACCESS),
                policy.granted());
    }

    @Test
    void grantedReturnsDefensiveCopy() {
        ToolPermissionPolicy policy = new ToolPermissionPolicy("FINANCE_ACCESS");

        Set<ToolPermission> firstCall = policy.granted();
        firstCall.add(ToolPermission.RUN_SHELL);

        assertFalse(policy.granted().contains(ToolPermission.RUN_SHELL));
    }

    @Test
    void requiredForKnownToolsMapsToCatalogPermission() {
        ToolPermissionPolicy policy = new ToolPermissionPolicy(null);

        assertEquals(EnumSet.of(ToolPermission.PLANNER_ACCESS), policy.requiredFor("create_todo"));
        assertEquals(EnumSet.of(ToolPermission.CALENDAR_ACCESS), policy.requiredFor("list_events"));
        assertEquals(EnumSet.of(ToolPermission.MEMORY_ACCESS), policy.requiredFor("memory_search"));
        assertEquals(EnumSet.of(ToolPermission.FINANCE_ACCESS), policy.requiredFor("finance_budget_status"));
    }

    @Test
    void requiredForUnknownToolIsEmpty() {
        ToolPermissionPolicy policy = new ToolPermissionPolicy(null);

        assertEquals(Set.of(), policy.requiredFor("some_unmapped_tool"));
        assertTrue(policy.isAllowed("some_unmapped_tool"));
        assertEquals(Set.of(), policy.missingFor("some_unmapped_tool"));
    }

    @Test
    void isAllowedReflectsDefaultGrantsForPlannerButNotFinance() {
        ToolPermissionPolicy policy = new ToolPermissionPolicy(null);

        assertTrue(policy.isAllowed("create_todo"));
        assertFalse(policy.isAllowed("finance_summary"));
        assertEquals(EnumSet.of(ToolPermission.FINANCE_ACCESS), policy.missingFor("finance_summary"));
    }

    @Test
    void requiredForIntentMatchesEachKeywordFamily() {
        ToolPermissionPolicy policy = new ToolPermissionPolicy(null);

        assertEquals(EnumSet.of(ToolPermission.FINANCE_ACCESS), policy.requiredForIntent("Transfer money now"));
        assertEquals(EnumSet.of(ToolPermission.RUN_SHELL), policy.requiredForIntent("run_command ls -la"));
        assertEquals(EnumSet.of(ToolPermission.WRITE_FILES), policy.requiredForIntent("please write_file report.txt"));
        assertEquals(EnumSet.of(ToolPermission.MEDIA_ACCESS), policy.requiredForIntent("ffmpeg transcode video"));
        assertEquals(EnumSet.of(ToolPermission.SMART_HOME_ACCESS), policy.requiredForIntent("turn off the light"));
        assertEquals(EnumSet.of(ToolPermission.PC_CONTROL), policy.requiredForIntent("take a screenshot"));
        assertEquals(Set.of(), policy.requiredForIntent("say hello to the weather"));
    }

    @Test
    void requiredForIntentHandlesNullAndBlank() {
        ToolPermissionPolicy policy = new ToolPermissionPolicy(null);

        assertEquals(Set.of(), policy.requiredForIntent(null));
        assertEquals(Set.of(), policy.requiredForIntent("   "));
    }

    @Test
    void isIntentAllowedAndMissingForIntentReflectGrants() {
        ToolPermissionPolicy policy = new ToolPermissionPolicy(null);

        assertTrue(policy.isIntentAllowed("open_url the browser"));
        assertFalse(policy.isIntentAllowed("purchase a subscription"));
        assertEquals(EnumSet.of(ToolPermission.FINANCE_ACCESS), policy.missingForIntent("purchase a subscription"));
    }
}
