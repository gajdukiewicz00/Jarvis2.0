package org.jarvis.orchestrator.assist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.orchestrator.client.LlmServiceClient;
import org.jarvis.orchestrator.dto.LlmChatRequest;
import org.jarvis.orchestrator.dto.LlmChatResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reasons with the local Qwen model via {@code llm-service} (which fronts the
 * host llama.cpp daemon). Asks for STRICT JSON {@code {answer, action{type,target}}}.
 * If the model is unavailable, returns {@link Reasoning#unavailable} — never a
 * fabricated answer.
 */
@Slf4j
@Component
public class LlmReasonerImpl implements LlmReasoner {

    private static final Pattern JSON_OBJ = Pattern.compile("\\{.*\\}", Pattern.DOTALL);
    private final LlmServiceClient llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmReasonerImpl(LlmServiceClient llm) { this.llm = llm; }

    @Override
    public Reasoning reason(String command, Map<String, Object> screenContext, List<String> memory,
                            String correlationId, String userId) {
        String sys = "You are Jarvis, a fully local desktop assistant. Use ONLY the provided "
                + "screen context and memory. Reply with STRICT JSON and nothing else: "
                + "{\"answer\": <1-2 sentences>, \"action\": {\"type\": "
                + "\"OPEN_APP|OPEN_URL|FOCUS_WINDOW|TYPE_TEXT|HOTKEY|NONE\", \"target\": <string>}}. "
                + "Prefer NONE unless the user clearly asked to open/launch something. "
                + "Never invent screen content you cannot see.";
        StringBuilder usr = new StringBuilder();
        usr.append("User command: ").append(command).append("\n");
        if (screenContext != null && !screenContext.isEmpty()) {
            usr.append("Active window: ").append(screenContext.getOrDefault("activeWindowTitle", "")).append("\n");
            usr.append("Tags: ").append(screenContext.getOrDefault("semanticTags", "")).append("\n");
            Object ocr = screenContext.get("ocrText");
            if (ocr != null) usr.append("OCR text:\n").append(truncate(ocr.toString(), 3000)).append("\n");
        }
        if (memory != null && !memory.isEmpty()) {
            usr.append("Relevant memory:\n");
            memory.stream().limit(5).forEach(m -> usr.append("- ").append(truncate(m, 300)).append("\n"));
        }
        try {
            LlmChatResponse resp = llm.chat(
                    new LlmChatRequest("assist-" + userId,
                            List.of(new LlmChatRequest.Message("system", sys),
                                    new LlmChatRequest.Message("user", usr.toString()))),
                    correlationId, userId, "default");
            String reply = resp == null ? null : resp.reply();
            if (reply == null || reply.isBlank()) {
                return Reasoning.unavailable("llm_empty_reply");
            }
            return parse(reply);
        } catch (Exception ex) {
            log.warn("assist LLM call failed cid={}: {}", correlationId, ex.getMessage());
            return Reasoning.unavailable("llm_unavailable: " + ex.getClass().getSimpleName());
        }
    }

    private Reasoning parse(String reply) {
        Matcher m = JSON_OBJ.matcher(reply);
        if (m.find()) {
            try {
                JsonNode n = mapper.readTree(m.group());
                String answer = n.path("answer").asText(reply).trim();
                JsonNode act = n.path("action");
                String type = act.path("type").asText("NONE");
                String target = act.path("target").asText("");
                return Reasoning.of(answer, type, target);
            } catch (Exception ignore) { /* fall through to raw */ }
        }
        return Reasoning.of(reply.trim(), "NONE", "");
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }
}
