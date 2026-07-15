package org.jarvis.voicegateway.client;

public interface PlannerActionGateway {

    /**
     * Result of asking the planner service to summarise the user's day for a voice reply.
     *
     * @param success        whether a real planner summary was produced
     * @param spokenSummary  ready-to-speak Russian/English summary (never blank on success)
     * @param failureReason  coded failure reason (prefix before ':') when {@code success} is false
     */
    record PlannerResult(boolean success, String spokenSummary, String failureReason) {}

    /**
     * Fetches the user's plan for today and builds a short, speakable summary.
     *
     * @param userId the end-user whose plan to read (never null/blank at call site)
     * @param lang   BCP-47-ish language tag ("ru-RU" / "en-US") used to localise the summary
     * @param action the resolved rule action name (e.g. PLANNER_TODAY, PLANNER_DAILY_SUMMARY)
     */
    PlannerResult summarizeDay(String userId, String lang, String action);
}
