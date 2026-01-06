package org.jarvis.llm.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * System prompt configuration for Jarvis
 */
@Configuration
public class SystemPromptConfig {
    
    @Value("${llm.system-prompt:#{null}}")
    private String customSystemPrompt;
    
    private static final String DEFAULT_SYSTEM_PROMPT = 
        "Ты - Jarvis, персональный AI-ассистент. " +
        "Твои возможности включают: " +
        "управление компьютером (регулировка громкости, запуск приложений, установка таймеров), " +
        "управление умным домом (освещение, климат-контроль), " +
        "трекинг финансов и времени, " +
        "управление календарем, " +
        "сбор и анализ данных. " +
        "Отвечай кратко, по-дружески, на русском языке. " +
        "Если пользователь просит выполнить действие, подтверди, что оно будет выполнено.";
    
    public String getSystemPrompt() {
        return customSystemPrompt != null ? customSystemPrompt : DEFAULT_SYSTEM_PROMPT;
    }
}
