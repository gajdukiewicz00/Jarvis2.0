package org.jarvis.voicegateway.service.intent;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class IntentResult {
    boolean handled;
    String action;
    String response;
    String correlationId;
    
    @Builder.Default
    Map<String, Object> parameters = Map.of();
}

