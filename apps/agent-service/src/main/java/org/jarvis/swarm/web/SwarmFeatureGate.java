package org.jarvis.swarm.web;

import org.jarvis.swarm.config.SwarmProperties;
import org.springframework.stereotype.Component;

/** Single chokepoint for the {@code swarm.enabled} feature flag. */
@Component
public class SwarmFeatureGate {

    private final SwarmProperties props;

    public SwarmFeatureGate(SwarmProperties props) {
        this.props = props;
    }

    public void ensureEnabled() {
        if (!props.enabled()) {
            throw new SwarmDisabledException();
        }
    }
}
