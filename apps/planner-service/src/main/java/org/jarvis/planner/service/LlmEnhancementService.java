package org.jarvis.planner.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.client.LlmServiceClient;
import org.jarvis.planner.dto.DailyPlanDto;
import org.springframework.stereotype.Service;

/**
 * LLM-based enhancement for plans and recommendations
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmEnhancementService {

    private final LlmServiceClient llmClient;

    /**
     * Enhance daily plan description with LLM
     */
    public String enhancePlanDescription(String userId, DailyPlanDto plan) {
        log.info("Enhancing plan description for user: {}", userId);

        StringBuilder planText = new StringBuilder();
        planText.append("План на день:\n");
        plan.getBlocks().forEach((block, activities) -> {
            planText.append(block).append(": ").append(String.join(", ", activities)).append("\n");
        });

        return llmClient.enhancePlanDescription(userId, planText.toString());
    }

    /**
     * Generate email/document via LLM
     */
    public String generateDocument(String userId, String documentType, String context) {
        log.info("Generating {} for user: {}", documentType, userId);

        // Placeholder: in future, call LLM with prompt engineering using documentType
        // and context
        // TODO: Implement actual LLM call
        return "Документ будет сгенерирован через LLM. Тип: " + documentType;
    }

    /**
     * Smart context-aware recommendations
     */
    public String generateSmartRecommendation(String userId, String context) {
        log.info("Generating smart recommendation for user: {} with context: {}", userId, context);

        // TODO: Call LLM with user context, habits, goals
        return "Умная рекомендация на основе контекста и истории пользователя";
    }

    /**
     * Natural language task creation
     */
    public String parseNaturalLanguageTask(String userId, String naturalLanguage) {
        log.info("Parsing NL task for user: {}: {}", userId, naturalLanguage);

        // TODO: Use LLM to extract: title, category, priority, dueDate from natural
        // language
        // Example: "Напомни позвонить маме завтра в 15:00"
        // → Task(title="Позвонить маме", dueDate="tomorrow 15:00", category=CALLS)

        return "Task будет создана из: " + naturalLanguage;
    }
}
