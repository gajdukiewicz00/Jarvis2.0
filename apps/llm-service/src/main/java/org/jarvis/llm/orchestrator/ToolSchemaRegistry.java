package org.jarvis.llm.orchestrator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class ToolSchemaRegistry {

    private static final String REGISTRY_PATH = "classpath:tools/registry.json";

    private final ResourceLoader resourceLoader;
    private String cachedRegistry;

    public ToolSchemaRegistry(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String renderForPrompt() {
        if (cachedRegistry == null) {
            cachedRegistry = loadRegistry();
        }
        return cachedRegistry;
    }

    private String loadRegistry() {
        Resource resource = resourceLoader.getResource(REGISTRY_PATH);
        if (!resource.exists()) {
            log.warn("Tool registry not found at {}", REGISTRY_PATH);
            return "[]";
        }
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to load tool registry: {}", e.getMessage());
            return "[]";
        }
    }
}
