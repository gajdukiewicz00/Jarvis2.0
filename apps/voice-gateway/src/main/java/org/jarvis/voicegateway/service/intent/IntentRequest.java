package org.jarvis.voicegateway.service.intent;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class IntentRequest {
    String text;
    String language;
    String sessionId;
    String correlationId;
}

