package org.jarvis.orchestrator.dto;

public record IntentExecutionResult(
        String responseText,
        boolean executorFound,
        boolean executionAttempted,
        boolean executionSucceeded,
        boolean executionFailed,
        String failureReason) {
}
