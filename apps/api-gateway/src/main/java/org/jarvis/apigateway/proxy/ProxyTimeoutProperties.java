package org.jarvis.apigateway.proxy;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Config-driven overrides for {@link ProxyTimeoutPolicy}, keyed by the same
 * {@code downstreamService} identifier passed to
 * {@link DownstreamProxyService#forward(jakarta.servlet.http.HttpServletRequest, String, String)}
 * (e.g. {@code "agent-service"}, {@code "media-service"}).
 * <p>
 * A service absent from these maps falls back to {@link ProxyTimeoutPolicy}'s hardcoded
 * defaults, so overriding is opt-in per service — see {@code gateway.proxy.timeout.*} in
 * application.yaml.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "gateway.proxy.timeout")
public class ProxyTimeoutProperties {

    private Map<String, Long> connectMsByService = new HashMap<>();
    private Map<String, Long> readMsByService = new HashMap<>();
}
