package org.jarvis.llm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typed configuration for the local AI model stack
 * (see {@code docs/architecture/local-ai-memory-stack.md}).
 *
 * <p>This class is wiring only — it does not change runtime behavior on its
 * own. The host model daemon is still served via
 * {@link HostModelDaemonProperties}; this surface lets operators declare
 * the human-readable model identity per channel and toggle the rerank /
 * external-network flags ahead of an eventual code-level cut-over from
 * Qwen to Gemma + Devstral.</p>
 *
 * <p>Defaults intentionally describe the <strong>currently deployed</strong>
 * stack so a fresh checkout reads truthfully. Flip via env vars or a
 * profile override when the swap actually lands.</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jarvis.ai")
public class JarvisAiModelsProperties {

    private final Models models = new Models();
    private final Embeddings embeddings = new Embeddings();
    private final Reranker reranker = new Reranker();
    private final Network network = new Network();

    @Getter
    @Setter
    public static class Models {
        private Channel router = new Channel();
        private Channel main = new Channel();
        private Channel coding = new Channel();
    }

    @Getter
    @Setter
    public static class Channel {
        /** Human-readable identifier, e.g. {@code gemma-3-27b-it-q4_k_m}. */
        private String id = "";
        /** Runtime hosting the channel: {@code llamacpp}, {@code ollama}, {@code vllm}, {@code tei}. */
        private String runtime = "";
        /** Endpoint URL. Local-only deployments must point at 127.0.0.1. */
        private String endpoint = "";
        /** Effective context length cap applied by the orchestrator. */
        private int contextLength = 0;
    }

    @Getter
    @Setter
    public static class Embeddings {
        private String id = "";
        private String runtime = "";
        private String endpoint = "";
        private int dimensions = 0;
    }

    @Getter
    @Setter
    public static class Reranker {
        private boolean enabled = false;
        private String id = "";
        private String runtime = "";
        private String endpoint = "";
    }

    @Getter
    @Setter
    public static class Network {
        /**
         * Master switch. When false, llm-service must reject any model
         * endpoint URL that is not on 127.0.0.1 / ::1 / localhost. The
         * existing {@code jarvis.llm.local-only} flag continues to gate
         * cloud AI URL detection at startup.
         */
        private boolean externalNetworkAllowed = false;
    }
}
