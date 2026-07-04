package org.jarvis.visionsecurity.service.cv;

import org.jarvis.visionsecurity.model.CvAnalysisResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Safe default {@link UiElementDetector}. Always reports
 * {@code NOT_CONFIGURED} and returns no elements — it never fabricates
 * detections. Replaced automatically when a real local detector bean is
 * registered ({@link ConditionalOnMissingBean}).
 */
@Component
@ConditionalOnMissingBean(value = UiElementDetector.class,
        ignored = NotConfiguredUiElementDetector.class)
public class NotConfiguredUiElementDetector implements UiElementDetector {

    public static final String ID = "not-configured";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DetectionResult detect(CvAnalysisResult context) {
        return DetectionResult.notConfigured(ID);
    }
}
