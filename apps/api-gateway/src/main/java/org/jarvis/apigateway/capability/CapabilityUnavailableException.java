package org.jarvis.apigateway.capability;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

public class CapabilityUnavailableException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final String downstreamService;
    private final String capability;
    private final RuntimeMode runtimeMode;
    private final List<String> supportedRuntimeModes;
    private final Map<String, Object> details;

    public CapabilityUnavailableException(HttpStatus status,
                                          String errorCode,
                                          String message,
                                          String downstreamService,
                                          String capability,
                                          RuntimeMode runtimeMode,
                                          List<String> supportedRuntimeModes,
                                          Map<String, Object> details) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.downstreamService = downstreamService;
        this.capability = capability;
        this.runtimeMode = runtimeMode;
        this.supportedRuntimeModes = supportedRuntimeModes == null ? List.of() : List.copyOf(supportedRuntimeModes);
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    public String downstreamService() {
        return downstreamService;
    }

    public String capability() {
        return capability;
    }

    public RuntimeMode runtimeMode() {
        return runtimeMode;
    }

    public List<String> supportedRuntimeModes() {
        return supportedRuntimeModes;
    }

    public Map<String, Object> details() {
        return details;
    }
}
