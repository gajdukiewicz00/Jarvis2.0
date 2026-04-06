package org.jarvis.voicegateway.service;

import java.util.Map;

public interface SttService {
    String transcribe(byte[] wav16kMonoPcm, String languageCode);

    /**
     * Creates a new session for streaming recognition.
     * 
     * @return A session object that handles audio chunks.
     */
    StreamingRecognitionSession createSession();

    /**
     * Creates a new session for streaming recognition with language hint.
     * Default implementation delegates to {@link #createSession()}.
     */
    default StreamingRecognitionSession createSession(String languageCode) {
        return createSession();
    }

    default String providerId() {
        return "unknown";
    }

    default Map<String, Object> describeRuntime() {
        return Map.of(
                "configuredProvider", providerId(),
                "status", "unknown",
                "available", false);
    }
}
