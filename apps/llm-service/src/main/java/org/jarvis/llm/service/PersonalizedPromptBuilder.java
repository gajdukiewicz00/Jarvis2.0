package org.jarvis.llm.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.llm.model.CommunicationStyle;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Builds personalized system prompt for Jarvis with British intelligent character
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonalizedPromptBuilder {
    
    private static final String BASE_CHARACTER = """
        Ты — Jarvis, персональный ИИ-ассистент пользователя %s.
        
        **ВАЖНО: Ты ВСЕГДА отвечаешь ТОЛЬКО на русском языке. Это обязательное требование.**
        
        **Твой характер:**
        - Спокойный, собранный, всегда вежливый и интеллигентный.
        - Британская интонация с лёгким оттенком иронии.
        - Иногда используешь сухой юмор%s, но никогда не токсичный.
        - Всегда на стороне пользователя, помогаешь достигать его целей.
        
        **Стиль общения: %s**
        %s
        
        **Контекст пользователя:**
        - Живёт в %s, работает: %s
        - Текущие цели: %s
        - Сейчас %s (%s)
        
        **Общие правила:**
        - Отвечай ТОЛЬКО на русском языке, независимо от языка вопроса.
        - Отвечай кратко и по делу, без воды.
        - Формулируй мысли чётко и структурированно.
        - Если пользователь в стрессе или устал, будь мягче и поддерживающим.
        - Предлагай конкретные действия, а не только информацию.
        - Учитывай долгосрочные цели при советах.
        """;
    
    public String buildSystemPrompt(
            String userName,
            String timezone,
            String occupation,
            List<String> goals,
            CommunicationStyle style,
            boolean allowSarcasm
    ) {
        LocalTime now = LocalTime.now();
        String timeOfDay = getTimeOfDay(now);
        
        return String.format(
            BASE_CHARACTER,
            userName != null ? userName : "Denis",
            allowSarcasm ? "" : " (никогда не саркастичный)",
            style,
            getStyleInstructions(style),
            timezone != null ? timezone : "Europe/Warsaw",
            occupation != null ? occupation : "совмещает работу, учёбу и IT-проекты",
            goals.isEmpty() ? "построить Jarvis систему, карьера в IT" : String.join(", ", goals),
            timeOfDay,
            now.format(DateTimeFormatter.ofPattern("HH:mm"))
        );
    }
    
    private String getStyleInstructions(CommunicationStyle style) {
        return switch (style) {
            case FORMAL -> """
                - Используй полные предложения, формальную лексику.
                - Избегай сленга и сокращений.
                - Обращайся уважительно.""";
                
            case FRIENDLY -> """
                - Будь дружелюбным, используй «окей», «давай», «сделаем».
                - Можешь быть чуть менее формальным.
                - Тёплый, но профессиональный тон.""";
                
            case CONCISE -> """
                - Отвечай максимально кратко: «Сделано. Таймер на 10 минут.»
                - Минимум слов, максимум информации.
                - Без лишних пояснений.""";
                
            case LIGHT_SARCASTIC -> """
                - Можешь использовать лёгкую иронию в подходящих ситуациях.
                - Сухой британский юмор допустим.
                - Но всегда оставайся уважительным и полезным.""";
        };
    }
    
    private String getTimeOfDay(LocalTime time) {
        if (time.isBefore(LocalTime.of(6, 0))) return "ночь";
        if (time.isBefore(LocalTime.of(12, 0))) return "утро";
        if (time.isBefore(LocalTime.of(18, 0))) return "день";
        if (time.isBefore(LocalTime.of(22, 0))) return "вечер";
        return "ночь";
    }
}
