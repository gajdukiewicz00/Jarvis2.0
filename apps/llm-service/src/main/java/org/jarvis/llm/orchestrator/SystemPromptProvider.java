package org.jarvis.llm.orchestrator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class SystemPromptProvider {

    private static final String PROMPT_PATH = "classpath:prompts/llm-orchestrator-system.txt";

    private final ResourceLoader resourceLoader;
    private String cachedPrompt;

    public SystemPromptProvider(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String getPrompt() {
        if (cachedPrompt == null) {
            cachedPrompt = loadPrompt();
        }
        return cachedPrompt;
    }

    private String loadPrompt() {
        Resource resource = resourceLoader.getResource(PROMPT_PATH);
        if (!resource.exists()) {
            log.warn("System prompt not found at {}", PROMPT_PATH);
            return "";
        }
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to load system prompt: {}", e.getMessage());
            return "";
        }
    }
}
