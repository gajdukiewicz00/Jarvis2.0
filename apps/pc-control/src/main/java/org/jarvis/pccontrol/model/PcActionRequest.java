package org.jarvis.pccontrol.model;

import java.util.Map;

public record PcActionRequest(String actionType, Map<String, String> parameters) {

    public PcActionRequest {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
