package org.jarvis.swarm.executor.role;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.executor.ExecutionContext;
import org.jarvis.swarm.executor.RoleExecutor;
import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.role.AgentRole;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FINANCE: read-only analysis of a finance summary. Always requires FINANCE_ACCESS
 * (role ∩ user) — a task without that grant is rejected. It NEVER creates a transaction;
 * any state-changing financial action would require an explicit confirmation gate, which
 * this analysis role does not perform.
 */
@Component
public class FinanceAgentExecutor implements RoleExecutor {

    private static final Pattern AMOUNT = Pattern.compile("(?i)(income|expense|expenses|spend|spent|balance)\\D{0,8}(\\d+(?:\\.\\d+)?)");

    @Override
    public AgentRole role() {
        return AgentRole.FINANCE;
    }

    @Override
    public RoleResult execute(ExecutionContext ctx) {
        ctx.checkpoint();
        // Finance work is gated: no FINANCE_ACCESS → rejected.
        ctx.guard().ensurePermission(ctx.task(), ToolPermission.FINANCE_ACCESS);

        String goal = ctx.task().goal();
        double income = 0;
        double expenses = 0;
        Matcher m = AMOUNT.matcher(goal);
        List<String> observations = new ArrayList<>();
        while (m.find()) {
            String label = m.group(1).toLowerCase();
            double value = Double.parseDouble(m.group(2));
            if (label.startsWith("income")) {
                income += value;
            } else if (label.startsWith("balance")) {
                observations.add("balance noted: " + value);
            } else {
                expenses += value;
            }
        }
        double net = income - expenses;
        List<String> risks = new ArrayList<>();
        if (expenses > income && income > 0) {
            risks.add("expenses exceed income (net " + net + ")");
        }

        String analysis = "# Finance Analysis\n\nIncome: " + income + "\nExpenses: " + expenses
                + "\nNet: " + net + "\n\nObservations:\n"
                + (observations.isEmpty() ? "- none\n" : "- " + String.join("\n- ", observations) + "\n");
        List<String> proposed = List.of("parse summary", "compute net", "flag overspend");
        List<String> next = List.of("No transactions created (would require explicit confirmation)");

        if (ctx.dryRun()) {
            return RoleResult.success("FINANCE analysis (dry-run)", analysis, List.of(), proposed, risks, next);
        }
        return RoleResult.success("FINANCE analyzed summary: net=" + net, analysis, List.of(),
                proposed, risks, next);
    }
}
