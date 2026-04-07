package org.jarvis.voicegateway.client;

import java.util.Map;

public interface PcControlActionGateway {

    record DispatchResult(
            String status,
            boolean executorFound,
            boolean executionAttempted,
            boolean executionSucceeded,
            boolean executionFailed,
            String failureReason,
            Map<String, Object> rawResponse) {
    }

    DispatchResult dispatch(String action, Map<String, Object> params, String userId, String correlationId);
}
