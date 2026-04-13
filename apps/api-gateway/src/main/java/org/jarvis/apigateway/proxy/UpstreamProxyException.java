package org.jarvis.apigateway.proxy;

import org.springframework.http.HttpStatus;

public class UpstreamProxyException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final String downstreamService;
    private final String upstreamPath;
    private final Integer upstreamStatus;
    private final Object upstreamBody;

    public UpstreamProxyException(HttpStatus status,
                                  String errorCode,
                                  String message,
                                  String downstreamService,
                                  String upstreamPath,
                                  Integer upstreamStatus,
                                  Object upstreamBody,
                                  Throwable cause) {
        super(message, cause);
        this.status = status;
        this.errorCode = errorCode;
        this.downstreamService = downstreamService;
        this.upstreamPath = upstreamPath;
        this.upstreamStatus = upstreamStatus;
        this.upstreamBody = upstreamBody;
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

    public String upstreamPath() {
        return upstreamPath;
    }

    public Integer upstreamStatus() {
        return upstreamStatus;
    }

    public Object upstreamBody() {
        return upstreamBody;
    }
}
