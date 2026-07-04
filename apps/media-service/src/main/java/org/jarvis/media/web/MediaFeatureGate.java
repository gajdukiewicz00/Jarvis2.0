package org.jarvis.media.web;

import org.jarvis.media.config.MediaProperties;
import org.springframework.stereotype.Component;

/** Single chokepoint for the {@code media.enabled} feature flag. */
@Component
public class MediaFeatureGate {

    private final MediaProperties props;

    public MediaFeatureGate(MediaProperties props) {
        this.props = props;
    }

    public void ensureEnabled() {
        if (!props.enabled()) {
            throw new MediaDisabledException();
        }
    }
}
