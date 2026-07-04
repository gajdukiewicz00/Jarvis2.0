package org.jarvis.visionsecurity.service.cv;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Picks the active {@link OcrEngine} based on
 * {@code vision-security.cv.engine}. Falls back to the first available
 * engine when the configured id is unknown — this preserves current
 * behaviour while leaving the door open for PaddleOCR/EasyOCR backends.
 */
@Slf4j
@Component
public class OcrEngineSelector {

    private final List<OcrEngine> engines;
    private final VisionSecurityProperties properties;

    public OcrEngineSelector(List<OcrEngine> engines, VisionSecurityProperties properties) {
        this.engines = engines;
        this.properties = properties;
    }

    public OcrEngine select() {
        String requested = properties.getCv().getEngine();
        if (requested != null && !requested.isBlank()) {
            String normalised = requested.toLowerCase(Locale.ROOT);
            for (OcrEngine engine : engines) {
                if (engine.id().equalsIgnoreCase(normalised)) {
                    return engine;
                }
            }
            log.warn("Configured cv.engine='{}' is not registered; falling back to '{}'",
                    requested, engines.isEmpty() ? "(none)" : engines.get(0).id());
        }
        if (engines.isEmpty()) {
            throw new IllegalStateException("No OcrEngine implementations registered");
        }
        return engines.get(0);
    }
}
