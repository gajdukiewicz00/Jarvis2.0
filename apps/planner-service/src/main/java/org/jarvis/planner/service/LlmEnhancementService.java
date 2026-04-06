package org.jarvis.planner.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.client.LlmServiceClient;
import org.jarvis.planner.dto.DailyPlanDto;
import org.springframework.stereotype.Service;

/**
 * Optional LLM adapter. This is not part of the planner domain core.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmEnhancementService {

    private final LlmServiceClient llmClient;

    public String enhancePlanDescription(String userId, DailyPlanDto plan) {
        log.info("Enhancing plan description for user: {}", userId);

        StringBuilder planText = new StringBuilder();
        planText.append("План на день:\n");
        plan.getBlocks().forEach((block, activities) -> {
            planText.append(block).append(": ").append(String.join(", ", activities)).append("\n");
        });

        return llmClient.enhancePlanDescription(userId, planText.toString());
    }

    public String generateDocument(String userId, String documentType, String context) {
        throw new UnsupportedOperationException(
                "Planner document generation is not implemented here; use llm-service directly");
    }

    public String generateSmartRecommendation(String userId, String context) {
        throw new UnsupportedOperationException(
                "Planner smart recommendations are rule-based; optional LLM recommendations live outside planner core");
    }

    public String parseNaturalLanguageTask(String userId, String naturalLanguage) {
        throw new UnsupportedOperationException(
                "Natural-language task parsing is not implemented in planner-service; use llm-service directly");
    }
}
