package org.jarvis.apigateway.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.JarvisHttpHeaders;
import org.jarvis.common.security.ServiceJwtProvider;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class DownstreamProxyService {

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "content-length",
            "host",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "x-service-token");
    private static final String SECURITY_SERVICE_NAME = "security-service";
    private static final String AUTHORIZATION_HEADER = "authorization";

    private final ConcurrentHashMap<Long, HttpClient> clientsByConnectTimeoutMs = new ConcurrentHashMap<>();
    private final ServiceJwtProvider serviceJwtProvider;
    private final ProxyTimeoutPolicy proxyTimeoutPolicy;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final boolean allowDefaultUserFallback;

    public DownstreamProxyService(ServiceJwtProvider serviceJwtProvider,
                                  ProxyTimeoutPolicy proxyTimeoutPolicy,
                                  ObjectMapper objectMapper,
                                  Environment environment,
                                  @Value("${gateway.feign.allow-default-user-fallback:false}") boolean allowDefaultUserFallback) {
        this.serviceJwtProvider = serviceJwtProvider;
        this.proxyTimeoutPolicy = proxyTimeoutPolicy;
        this.objectMapper = objectMapper;
        this.environment = environment;
        this.allowDefaultUserFallback = allowDefaultUserFallback;
    }

    public ResponseEntity<byte[]> forward(HttpServletRequest request,
                                          String downstreamService,
                                          String upstreamBaseUrl) {
        return forward(request, downstreamService, upstreamBaseUrl, request.getRequestURI(), request.getRequestURI());
    }

    public ResponseEntity<byte[]> forward(HttpServletRequest request,
                                          String downstreamService,
                                          String upstreamBaseUrl,
                                          String gatewayPrefix,
                                          String upstreamPrefix) {
        String targetPath = request.getRequestURI();
        try {
            targetPath = resolveTargetPath(request, gatewayPrefix, upstreamPrefix);
            URI targetUri = buildTargetUri(upstreamBaseUrl, targetPath, request.getQueryString());
            byte[] requestBody = readRequestBody(request);
            Duration connectTimeout = proxyTimeoutPolicy.connectTimeout(downstreamService);

            HttpRequest.Builder builder = HttpRequest.newBuilder(targetUri)
                    .timeout(proxyTimeoutPolicy.readTimeout(downstreamService));
            copyRequestHeaders(request, builder, shouldForwardAuthorization(downstreamService));
            applyServiceHeaders(builder, downstreamService);
            applyDevFallbackHeaders(builder);

            HttpRequest.BodyPublisher bodyPublisher = bodyPublisher(requestBody);
            builder.method(request.getMethod(), bodyPublisher);

            log.debug("Forwarding {} {} -> {} [{}]", request.getMethod(), request.getRequestURI(), targetUri, downstreamService);

            HttpResponse<byte[]> upstreamResponse = clientFor(connectTimeout).send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (upstreamResponse.statusCode() >= 400) {
                throw buildUpstreamStatusException(downstreamService, targetPath, upstreamResponse);
            }

            return ResponseEntity.status(upstreamResponse.statusCode())
                    .headers(toResponseHeaders(upstreamResponse.headers().map()))
                    .body(upstreamResponse.body());
        } catch (UpstreamProxyException ex) {
            throw ex;
        } catch (HttpTimeoutException ex) {
            throw new UpstreamProxyException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "UPSTREAM_TIMEOUT",
                    "Timed out waiting for upstream service",
                    downstreamService,
                    targetPath,
                    null,
                    null,
                    ex);
        } catch (ConnectException ex) {
            throw new UpstreamProxyException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "UPSTREAM_CONNECTION_REFUSED",
                    "Connection refused to upstream service",
                    downstreamService,
                    targetPath,
                    null,
                    null,
                    ex);
        } catch (UnknownHostException ex) {
            throw new UpstreamProxyException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "UPSTREAM_HOST_NOT_FOUND",
                    "Upstream host could not be resolved",
                    downstreamService,
                    targetPath,
                    null,
                    null,
                    ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new UpstreamProxyException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "UPSTREAM_INTERRUPTED",
                    "Proxy request was interrupted",
                    downstreamService,
                    targetPath,
                    null,
                    null,
                    ex);
        } catch (IOException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof ConnectException connectException) {
                throw new UpstreamProxyException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "UPSTREAM_CONNECTION_REFUSED",
                        "Connection refused to upstream service",
                        downstreamService,
                        targetPath,
                        null,
                        null,
                        connectException);
            }
            if (cause instanceof UnknownHostException unknownHostException) {
                throw new UpstreamProxyException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "UPSTREAM_HOST_NOT_FOUND",
                        "Upstream host could not be resolved",
                        downstreamService,
                        targetPath,
                        null,
                        null,
                        unknownHostException);
            }
            throw new UpstreamProxyException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "UPSTREAM_UNAVAILABLE",
                    "Upstream service is unavailable",
                    downstreamService,
                    targetPath,
                    null,
                    null,
                    ex);
        }
    }

    private String resolveTargetPath(HttpServletRequest request, String gatewayPrefix, String upstreamPrefix) {
        String requestUri = request.getRequestURI();
        String suffix = requestUri.startsWith(gatewayPrefix)
                ? requestUri.substring(gatewayPrefix.length())
                : requestUri;
        if (suffix.isEmpty()) {
            suffix = "";
        }
        if (!suffix.isEmpty() && !suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        String normalizedPrefix = normalizePath(upstreamPrefix);
        return normalizedPrefix + suffix;
    }

    private URI buildTargetUri(String upstreamBaseUrl, String targetPath, String queryString) {
        String normalizedBaseUrl = upstreamBaseUrl.endsWith("/")
                ? upstreamBaseUrl.substring(0, upstreamBaseUrl.length() - 1)
                : upstreamBaseUrl;
        String normalizedPath = normalizePath(targetPath);
        StringBuilder target = new StringBuilder(normalizedBaseUrl).append(normalizedPath);
        if (queryString != null && !queryString.isBlank()) {
            target.append('?').append(queryString);
        }
        return URI.create(target.toString());
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private HttpClient clientFor(Duration connectTimeout) {
        long timeoutMs = connectTimeout.toMillis();
        return clientsByConnectTimeoutMs.computeIfAbsent(timeoutMs, ignored ->
                HttpClient.newBuilder()
                        .connectTimeout(connectTimeout)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build());
    }

    private void copyRequestHeaders(HttpServletRequest request,
                                    HttpRequest.Builder builder,
                                    boolean forwardAuthorization) {
        var headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            if (headerName == null || HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase())) {
                continue;
            }
            if (!forwardAuthorization && AUTHORIZATION_HEADER.equalsIgnoreCase(headerName)) {
                continue;
            }
            var values = request.getHeaders(headerName);
            while (values.hasMoreElements()) {
                builder.header(headerName, values.nextElement());
            }
        }

        String traceId = MDC.get(JarvisHttpHeaders.TRACE_ID_MDC_KEY);
        if (traceId != null && !traceId.isBlank() && request.getHeader(JarvisHttpHeaders.TRACE_ID) == null) {
            builder.header(JarvisHttpHeaders.TRACE_ID, traceId);
        }
    }

    private void applyServiceHeaders(HttpRequest.Builder builder, String downstreamService) {
        if (!serviceJwtProvider.isEnabled() || SECURITY_SERVICE_NAME.equals(downstreamService)) {
            return;
        }
        builder.header("X-Service-Token", serviceJwtProvider.createToken("api-gateway", List.of("SVC_INTERNAL")));
    }

    private boolean shouldForwardAuthorization(String downstreamService) {
        return SECURITY_SERVICE_NAME.equals(downstreamService);
    }

    private void applyDevFallbackHeaders(HttpRequest.Builder builder) {
        if (!allowDefaultUserFallback || !environment.acceptsProfiles(Profiles.of("dev"))) {
            return;
        }
        ensureHeader(builder, "X-User-Id", "dev-user");
        ensureHeader(builder, "X-Username", "dev-user");
        ensureHeader(builder, "X-User-Role", "USER");
        ensureHeader(builder, "X-User-Roles", "USER");
    }

    private void ensureHeader(HttpRequest.Builder builder, String headerName, String value) {
        boolean present = builder.build().headers().firstValue(headerName).isPresent();
        if (!present) {
            builder.header(headerName, value);
        }
    }

    private byte[] readRequestBody(HttpServletRequest request) throws IOException {
        return StreamUtils.copyToByteArray(request.getInputStream());
    }

    private HttpRequest.BodyPublisher bodyPublisher(byte[] requestBody) {
        return requestBody.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(requestBody);
    }

    private HttpHeaders toResponseHeaders(Map<String, List<String>> upstreamHeaders) {
        HttpHeaders responseHeaders = new HttpHeaders();
        upstreamHeaders.forEach((headerName, values) -> {
            if (headerName == null || HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase())) {
                return;
            }
            responseHeaders.put(headerName, values);
        });
        // Defensive sweep: Spring's HttpHeaders is case-insensitive, but if any
        // hop-by-hop header slipped through (e.g. due to upstream casing variants
        // that bypassed the filter loop), ensure they are gone. Duplicate
        // Transfer-Encoding from upstream + Tomcat causes nginx-ingress to 502.
        HOP_BY_HOP_HEADERS.forEach(responseHeaders::remove);
        return responseHeaders;
    }

    private UpstreamProxyException buildUpstreamStatusException(String downstreamService,
                                                               String upstreamPath,
                                                               HttpResponse<byte[]> upstreamResponse) {
        int upstreamStatus = upstreamResponse.statusCode();
        HttpStatus status;
        String errorCode;
        if (upstreamStatus == 401 || upstreamStatus == 403) {
            status = HttpStatus.valueOf(upstreamStatus);
            errorCode = "UPSTREAM_AUTH_FAILURE";
        } else if (upstreamStatus == 404) {
            status = HttpStatus.NOT_FOUND;
            errorCode = "UPSTREAM_NOT_FOUND";
        } else if (upstreamStatus == 503) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            errorCode = "UPSTREAM_UNHEALTHY";
        } else if (upstreamStatus >= 500) {
            status = HttpStatus.BAD_GATEWAY;
            errorCode = "UPSTREAM_ERROR";
        } else {
            status = HttpStatus.valueOf(upstreamStatus);
            errorCode = "UPSTREAM_CLIENT_ERROR";
        }

        return new UpstreamProxyException(
                status,
                errorCode,
                "Upstream service returned an error response",
                downstreamService,
                upstreamPath,
                upstreamStatus,
                parseUpstreamBody(upstreamResponse.body(), upstreamResponse.headers().firstValue("content-type").orElse(null)),
                null);
    }

    private Object parseUpstreamBody(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            return null;
        }
        boolean json = contentType != null && contentType.toLowerCase().contains("json");
        String bodyText = new String(body, StandardCharsets.UTF_8);
        if (json || bodyText.startsWith("{") || bodyText.startsWith("[")) {
            try {
                return objectMapper.readValue(bodyText, Object.class);
            } catch (IOException ignored) {
                // Fall back to string body below.
            }
        }
        return bodyText;
    }
}
