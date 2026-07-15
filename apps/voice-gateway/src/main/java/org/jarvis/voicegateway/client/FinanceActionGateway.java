package org.jarvis.voicegateway.client;

public interface FinanceActionGateway {

    /**
     * @param success       whether a real finance summary was produced
     * @param spokenSummary ready-to-speak Russian/English summary (never blank on success)
     * @param failureReason coded failure reason (prefix before ':') when {@code success} is false
     */
    record FinanceResult(boolean success, String spokenSummary, String failureReason) {}

    /** Fetches the user's finance summary and builds a short, speakable summary. */
    FinanceResult summarize(String userId, String lang, String action);
}
