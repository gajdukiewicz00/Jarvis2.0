package org.jarvis.visionsecurity.service.cv;

import org.jarvis.visionsecurity.model.CvAnalysisResult;

import java.nio.file.Path;

/**
 * Local Vision-Language Model adapter. Implementations must run 100%
 * locally — Jarvis explicitly forbids cloud vision APIs (no OpenAI,
 * Gemini, Claude, etc.). The default implementation returns
 * {@code NOT_CONFIGURED} and refuses to fabricate a summary; only swap it
 * out once a real local VLM (llama.cpp + LLaVA, Ollama with a vision
 * model, etc.) is wired up.
 */
public interface LocalVlmAdapter {

    String id();

    /** Backend model identifier, when known (e.g. {@code llava}). */
    default String model() { return ""; }

    /**
     * Best-effort high-level description of the screen captured in
     * {@code context}. Implementations MUST NOT call any remote service.
     */
    VlmResult summarise(CvAnalysisResult context);

    /**
     * Answer a free-form question about the screenshot at
     * {@code imagePath}, with {@code context} as supporting OCR/window
     * metadata. Default implementation falls back to
     * {@link #summarise(CvAnalysisResult)} so adapters that only know how
     * to summarise still work.
     */
    default VlmResult answer(String question, Path imagePath, CvAnalysisResult context) {
        return summarise(context);
    }

    /**
     * Tri-state availability so the caller can render a useful message
     * without parsing free-form text.
     */
    enum Availability {
        /** Adapter is wired and ready. */
        READY,
        /** No local VLM is configured. The adapter MUST NOT fabricate output. */
        NOT_CONFIGURED,
        /** Adapter is configured but cannot reach its local backend. */
        UNAVAILABLE
    }

    record VlmResult(
            Availability availability,
            String engine,
            String summary,
            String error,
            long durationMs
    ) {
        public static VlmResult notConfigured(String engine) {
            return new VlmResult(
                    Availability.NOT_CONFIGURED,
                    engine,
                    null,
                    "Local VLM not configured. No cloud APIs will ever be used; "
                            + "wire a local backend (e.g. llama.cpp + LLaVA, Ollama with "
                            + "a vision model) and provide a LocalVlmAdapter bean.",
                    0L);
        }

        public static VlmResult unavailable(String engine, String reason, long durationMs) {
            return new VlmResult(Availability.UNAVAILABLE, engine, null, reason, durationMs);
        }

        public static VlmResult success(String engine, String summary, long durationMs) {
            return new VlmResult(Availability.READY, engine, summary, null, durationMs);
        }
    }
}
