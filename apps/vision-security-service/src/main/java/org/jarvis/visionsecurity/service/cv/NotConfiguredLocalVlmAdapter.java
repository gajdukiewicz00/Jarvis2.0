package org.jarvis.visionsecurity.service.cv;

import org.jarvis.visionsecurity.model.CvAnalysisResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Safe default {@link LocalVlmAdapter}: always returns
 * {@link LocalVlmAdapter.Availability#NOT_CONFIGURED} so callers can
 * surface a clear error to the user. It MUST NOT invent a summary —
 * "no VLM" is a feature, not a bug.
 *
 * <p>Once a real local VLM lands (e.g. llama.cpp + LLaVA), expose a
 * primary {@link LocalVlmAdapter} bean and this default will step aside
 * thanks to {@link ConditionalOnMissingBean}.</p>
 */
@Component
@ConditionalOnMissingBean(value = LocalVlmAdapter.class,
        ignored = NotConfiguredLocalVlmAdapter.class)
public class NotConfiguredLocalVlmAdapter implements LocalVlmAdapter {

    public static final String ID = "local-vlm-not-configured";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public VlmResult summarise(CvAnalysisResult context) {
        return VlmResult.notConfigured(ID);
    }
}
