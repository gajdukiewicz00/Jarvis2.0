package org.jarvis.swarm.executor.role;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.executor.ExecutionContext;
import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.permission.PermissionDeniedException;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.sandbox.Sandbox;
import org.jarvis.swarm.support.SwarmTestFactory;
import org.jarvis.swarm.task.AgentTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinanceAgentExecutorTest {

    @TempDir
    Path tmp;

    private ExecutionContext ctx(String goal, Set<ToolPermission> requested, Set<ToolPermission> granted,
                                 boolean dryRun) {
        var engine = SwarmTestFactory.engine(tmp, "READ_FILES");
        Sandbox sb = engine.sandbox().create("finance-" + Math.abs(goal.hashCode()));
        AgentTask task = SwarmTestFactory.task(AgentRole.FINANCE, goal, requested, granted, dryRun);
        return SwarmTestFactory.context(task, sb, engine.guard());
    }

    @Test
    void rejectedWithoutFinanceAccessGrant() {
        assertThatThrownBy(() -> new FinanceAgentExecutor()
                .execute(ctx("income 1000", Set.of(ToolPermission.FINANCE_ACCESS), Set.of(), false)))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void computesNetAndFlagsOverspend() {
        RoleResult result = new FinanceAgentExecutor().execute(
                ctx("income 1000, expenses 1400", Set.of(ToolPermission.FINANCE_ACCESS),
                        Set.of(ToolPermission.FINANCE_ACCESS), false));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("Income: 1000.0").contains("Expenses: 1400.0").contains("Net: -400.0");
        assertThat(result.risks()).anyMatch(r -> r.contains("expenses exceed income"));
        assertThat(result.summary()).contains("net=-400.0");
        assertThat(result.nextActions()).anyMatch(n -> n.contains("No transactions created"));
    }

    @Test
    void noOverspendRiskWhenIncomeCoversExpenses() {
        RoleResult result = new FinanceAgentExecutor().execute(
                ctx("income 2000 and spend 500", Set.of(ToolPermission.FINANCE_ACCESS),
                        Set.of(ToolPermission.FINANCE_ACCESS), false));

        assertThat(result.risks()).isEmpty();
        assertThat(result.output()).contains("Net: 1500.0");
    }

    @Test
    void balanceMentionIsRecordedAsObservationNotIncomeOrExpense() {
        RoleResult result = new FinanceAgentExecutor().execute(
                ctx("current balance 250", Set.of(ToolPermission.FINANCE_ACCESS),
                        Set.of(ToolPermission.FINANCE_ACCESS), false));

        assertThat(result.output()).contains("balance noted: 250.0");
        assertThat(result.output()).contains("Income: 0.0").contains("Expenses: 0.0");
        assertThat(result.risks()).isEmpty();
    }

    @Test
    void noRecognizedAmountsYieldsZeroedAnalysisWithNoObservations() {
        RoleResult result = new FinanceAgentExecutor().execute(
                ctx("summarize my month please", Set.of(ToolPermission.FINANCE_ACCESS),
                        Set.of(ToolPermission.FINANCE_ACCESS), false));

        assertThat(result.output()).contains("Observations:\n- none");
    }

    @Test
    void dryRunSummaryDiffersFromRealRun() {
        RoleResult result = new FinanceAgentExecutor().execute(
                ctx("income 500", Set.of(ToolPermission.FINANCE_ACCESS),
                        Set.of(ToolPermission.FINANCE_ACCESS), true));

        assertThat(result.summary()).contains("dry-run");
    }
}
