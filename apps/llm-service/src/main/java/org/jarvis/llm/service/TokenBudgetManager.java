package org.jarvis.llm.service;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.llm.dto.ChatMessageDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages token budget for LLM requests.
 * Ensures we don't exceed context limits by trimming history.
 * 
 * Token estimation: ~4 characters per token for mixed Russian/English.
 */
@Slf4j
@Component
public class TokenBudgetManager {

    @Value("${llm.budget.system-prompt:500}")
    private int systemPromptBudget;

    @Value("${llm.budget.memory-context:600}")
    private int memoryContextBudget;

    @Value("${llm.budget.history:1400}")
    private int historyBudget;

    @Value("${llm.budget.total:3500}")
    private int totalBudget;

    private static final int CHARS_PER_TOKEN = 4;

    /**
     * Build messages list with token budget enforcement.
     * 
     * @param systemPrompt System prompt
     * @param memoryContext Context from memory search
     * @param history Conversation history
     * @param userMessage Current user message
     * @return List of messages within token budget
     */
    public List<ChatMessageDto> buildMessages(
            String systemPrompt,
            String memoryContext,
            List<ChatMessageDto> history,
            String userMessage) {

        List<ChatMessageDto> messages = new ArrayList<>();
        int usedTokens = 0;

        // 1. System prompt (required, but truncate if too long)
        String truncatedSystemPrompt = truncateToTokens(systemPrompt, systemPromptBudget);
        usedTokens += estimateTokens(truncatedSystemPrompt);

        // 2. Add memory context to system prompt if available
        if (memoryContext != null && !memoryContext.isBlank()) {
            String truncatedMemory = truncateToTokens(memoryContext, memoryContextBudget);
            int memoryTokens = estimateTokens(truncatedMemory);
            
            if (usedTokens + memoryTokens <= totalBudget - historyBudget - 100) {
                truncatedSystemPrompt = truncatedSystemPrompt + 
                        "\n\n--- ПАМЯТЬ (релевантный контекст из прошлых разговоров) ---\n" +
                        truncatedMemory +
                        "\n--- КОНЕЦ ПАМЯТИ ---";
                usedTokens += memoryTokens + 20; // +20 for markers
                log.debug("Added memory context: {} tokens", memoryTokens);
            }
        }

        // Add system message
        messages.add(new ChatMessageDto(ChatMessageDto.Role.SYSTEM, truncatedSystemPrompt));

        // 3. User message (required)
        int userTokens = estimateTokens(userMessage);
        usedTokens += userTokens;

        // 4. History (trim oldest if over budget)
        int availableForHistory = totalBudget - usedTokens - 100; // 100 token buffer
        availableForHistory = Math.min(availableForHistory, historyBudget);

        List<ChatMessageDto> trimmedHistory = trimHistory(history, availableForHistory);
        messages.addAll(trimmedHistory);
        usedTokens += estimateTokens(trimmedHistory);

        // 5. Add current user message
        messages.add(new ChatMessageDto(ChatMessageDto.Role.USER, userMessage));

        log.debug("Token budget: system={}, memory={}, history={}, user={}, total={}",
                estimateTokens(truncatedSystemPrompt),
                memoryContext != null ? estimateTokens(memoryContext) : 0,
                estimateTokens(trimmedHistory),
                userTokens,
                usedTokens);

        return messages;
    }

    /**
     * Trim history to fit within token budget, keeping most recent messages.
     */
    private List<ChatMessageDto> trimHistory(List<ChatMessageDto> history, int maxTokens) {
        if (history == null || history.isEmpty()) {
            return new ArrayList<>();
        }

        // Calculate total tokens
        List<Integer> tokenCounts = history.stream()
                .map(m -> estimateTokens(m.getContent()))
                .toList();

        int totalTokens = tokenCounts.stream().mapToInt(Integer::intValue).sum();

        if (totalTokens <= maxTokens) {
            return new ArrayList<>(history);
        }

        // Trim from the beginning (oldest messages)
        List<ChatMessageDto> result = new ArrayList<>();
        int currentTokens = 0;

        // Start from the end (most recent)
        for (int i = history.size() - 1; i >= 0; i--) {
            int msgTokens = tokenCounts.get(i);
            if (currentTokens + msgTokens <= maxTokens) {
                result.add(0, history.get(i)); // Add to beginning
                currentTokens += msgTokens;
            } else {
                break;
            }
        }

        if (result.size() < history.size()) {
            log.debug("Trimmed history from {} to {} messages ({} tokens)",
                    history.size(), result.size(), currentTokens);
        }

        return result;
    }

    /**
     * Estimate token count for text
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.length() / CHARS_PER_TOKEN;
    }

    /**
     * Estimate token count for messages
     */
    public int estimateTokens(List<ChatMessageDto> messages) {
        if (messages == null) {
            return 0;
        }
        return messages.stream()
                .mapToInt(m -> estimateTokens(m.getContent()) + 4) // +4 for role marker
                .sum();
    }

    /**
     * Truncate text to fit within token limit
     */
    private String truncateToTokens(String text, int maxTokens) {
        if (text == null) {
            return "";
        }
        int maxChars = maxTokens * CHARS_PER_TOKEN;
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars - 3) + "...";
    }

    /**
     * Get remaining token budget for generation
     */
    public int getRemainingBudget(int usedTokens, int contextSize) {
        return Math.max(100, contextSize - usedTokens - 50);
    }
}



