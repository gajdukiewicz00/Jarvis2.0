package org.jarvis.voicegateway.service.intent;

public interface IntentHandler {
    boolean canHandle(IntentRequest request);

    IntentResult handle(IntentRequest request);
}

