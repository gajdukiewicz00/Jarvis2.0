package org.jarvis.apigateway.capability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class RuntimeModeResolver {

    private final Environment environment;
    private final String configuredRuntimeMode;

    public RuntimeModeResolver(Environment environment,
                               @Value("${jarvis.runtime.mode:}") String configuredRuntimeMode) {
        this.environment = environment;
        this.configuredRuntimeMode = configuredRuntimeMode;
    }

    public RuntimeMode currentMode() {
        RuntimeMode configured = RuntimeMode.from(configuredRuntimeMode);
        if (configured != RuntimeMode.UNKNOWN) {
            return configured;
        }
        if (environment.acceptsProfiles(Profiles.of("dev"))) {
            return RuntimeMode.DEV;
        }
        if (System.getenv("KUBERNETES_SERVICE_HOST") != null) {
            return RuntimeMode.K8S;
        }
        return RuntimeMode.LOCAL;
    }
}
