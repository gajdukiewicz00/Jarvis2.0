package org.jarvis.voicegateway.service.intent;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.voicegateway.util.LanguageDetector;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Simple keyword-based intent handler for common desktop actions.
 * Designed to be easily replaceable/extendable.
 * 
 * <h2>Language Detection</h2>
 * Uses {@link LanguageDetector} to auto-detect language from the transcript
 * if not explicitly provided. The detected language is passed to the orchestrator
 * for generating appropriate cinematic responses.
 * 
 * <h2>Supported Commands</h2>
 * <ul>
 *   <li>Volume control (громче/тише, louder/quieter)</li>
 *   <li>Media control (play, pause, next, previous)</li>
 *   <li>App launching (browser, terminal, YouTube)</li>
 *   <li>Window management (minimize, maximize, lock)</li>
 *   <li>Scenarios (work mode, rest mode, party, clean slate)</li>
 * </ul>
 */
@Slf4j
@Component
public class BasicIntentHandler implements IntentHandler {

    @Override
    public boolean canHandle(IntentRequest request) {
        return request != null && request.getText() != null && !request.getText().isBlank();
    }

    @Override
    public IntentResult handle(IntentRequest request) {
        String rawText = request.getText().toLowerCase(Locale.ROOT).trim();
        String correlationId = request.getCorrelationId();
        
        // Auto-detect language from transcript if not provided
        String lang = request.getLanguage();
        if (lang == null || lang.isBlank()) {
            lang = LanguageDetector.detect(request.getText());
        } else {
            lang = lang.toLowerCase(Locale.ROOT);
        }

        // Normalize common STT misrecognitions before matching
        String text = normalizeSttErrors(rawText);
        
        log.info("🔍 Parsing intent: text='{}' (raw='{}'), detectedLang={}, correlationId={}", 
                text, rawText, lang, correlationId);

        // ==================== Volume Controls ====================
        // VOLUME UP - Russian patterns (including Vosk variations like "сделать" vs "сделай")
        if (matchesAny(text, 
                "громче", "погромче", "прибавь", "прибавь звук", "прибавь громкость",
                "сделай громче", "сделать громче", "сделай погромче", "сделать погромче",
                "увеличь громкость", "увеличить громкость", "увеличь звук", "увеличить звук",
                "добавь громкости", "добавить громкости", "побольше громкости", "звук громче")) {
            log.info("✅ Matched VOLUME_UP (RU), correlationId={}", correlationId);
            return intentResult("VOLUME_UP", lang, correlationId, Map.of("delta", 10));
        }
        
        // VOLUME UP - English patterns
        if (matchesAny(text,
                "volume up", "louder", "turn it up", "make it louder", "increase volume",
                "turn up the volume", "raise the volume", "more volume", "crank it up")) {
            log.info("✅ Matched VOLUME_UP (EN), correlationId={}", correlationId);
            return intentResult("VOLUME_UP", lang, correlationId, Map.of("delta", 10));
        }
        
        // VOLUME DOWN - Russian patterns (including Vosk variations)
        if (matchesAny(text,
                "тише", "потише", "убавь", "убавь звук", "убавь громкость", "убавить",
                "сделай тише", "сделать тише", "сделай потише", "сделать потише",
                "уменьши громкость", "уменьшить громкость", "уменьши звук", "уменьшить звук",
                "поменьше громкости", "звук тише", "приглуши", "приглушить")) {
            log.info("✅ Matched VOLUME_DOWN (RU), correlationId={}", correlationId);
            return intentResult("VOLUME_DOWN", lang, correlationId, Map.of("delta", 10));
        }
        
        // VOLUME DOWN - English patterns  
        if (matchesAny(text,
                "volume down", "quieter", "turn it down", "make it quieter", "decrease volume",
                "turn down the volume", "lower the volume", "less volume", "softer")) {
            log.info("✅ Matched VOLUME_DOWN (EN), correlationId={}", correlationId);
            return intentResult("VOLUME_DOWN", lang, correlationId, Map.of("delta", 10));
        }
        
        // MUTE
        if (matchesAny(text, "выключи звук", "заглуши", "замолчи", "отключи звук", "mute", "silence")) {
            log.info("✅ Matched MUTE, correlationId={}", correlationId);
            return intentResult("MUTE", lang, correlationId, Map.of());
        }
        
        // UNMUTE
        if (matchesAny(text, "включи звук", "верни звук", "включить звук", "unmute")) {
            log.info("✅ Matched UNMUTE, correlationId={}", correlationId);
            return intentResult("UNMUTE", lang, correlationId, Map.of());
        }
        
        // SET_VOLUME (100%) - Russian patterns for "max volume"
        if (matchesAny(text,
                "громкость на максимум", "на максимум", "максимальная громкость",
                "громкость на полную", "на полную", "на полную громкость",
                "громкость сто", "громкость 100", "звук на максимум",
                "volume max", "max volume", "maximum volume", "volume 100",
                "full volume", "volume to max", "set volume to 100")) {
            log.info("✅ Matched SET_VOLUME(100), correlationId={}", correlationId);
            return intentResult("SET_VOLUME", lang, correlationId, Map.of("level", 100));
        }

        // ==================== Media Controls ====================
        if (matchesAny(text, "следующий", "следующий трек", "следующая песня", "next", "skip", "next track")) {
            log.info("✅ Matched MEDIA_NEXT, correlationId={}", correlationId);
            return intentResult("MEDIA_NEXT", lang, correlationId, Map.of());
        }
        
        if (matchesAny(text, "предыдущий", "предыдущий трек", "предыдущая песня", "previous", "prev", "previous track")) {
            log.info("✅ Matched MEDIA_PREV, correlationId={}", correlationId);
            return intentResult("MEDIA_PREV", lang, correlationId, Map.of());
        }
        
        // PAUSE - Russian patterns (including common variations)
        if (matchesAny(text, 
                "пауза", "стоп", "остановить", "остановись",
                "поставь на паузу", "ставь на паузу", "на паузу",
                "поставить на паузу", "ставить на паузу",
                "pause", "stop", "halt")) {
            log.info("✅ Matched PAUSE, correlationId={}", correlationId);
            return intentResult("PAUSE", lang, correlationId, Map.of());
        }
        
        if (matchesAny(text, "воспроизведи", "играй", "продолжи", "запусти музыку", "play", "resume")) {
            log.info("✅ Matched PLAY, correlationId={}", correlationId);
            return intentResult("PLAY", lang, correlationId, Map.of());
        }
        
        if (matchesAny(text, "play pause", "play/pause", "плей пауза")) {
            log.info("✅ Matched MEDIA_TOGGLE, correlationId={}", correlationId);
            return intentResult("MEDIA_TOGGLE", lang, correlationId, Map.of());
        }

        // ==================== App Launch ====================
        if (matchesAny(text, "открой браузер", "запусти браузер", "open browser", "launch browser")) {
            log.info("✅ Matched OPEN_BROWSER, correlationId={}", correlationId);
            return intentResult("OPEN_BROWSER", lang, correlationId, Map.of("app", "browser"));
        }
        
        if (matchesAny(text, "открой блокнот", "notepad", "open notepad", "запусти блокнот")) {
            log.info("✅ Matched OPEN_NOTEPAD, correlationId={}", correlationId);
            return intentResult("OPEN_NOTEPAD", lang, correlationId, Map.of("app", "notepad"));
        }
        
        if (matchesAny(text, "открой терминал", "terminal", "open terminal", "консоль", "открой консоль")) {
            log.info("✅ Matched OPEN_TERMINAL, correlationId={}", correlationId);
            return intentResult("OPEN_TERMINAL", lang, correlationId, Map.of("app", "terminal"));
        }
        
        if (matchesAny(text, "открой youtube", "ютуб", "ютьюб", "open youtube")) {
            log.info("✅ Matched OPEN_YOUTUBE, correlationId={}", correlationId);
            return intentResult("OPEN_YOUTUBE", lang, correlationId, Map.of("app", "youtube"));
        }

        // ==================== Window Controls ====================
        if (matchesAny(text, "сверни окно", "сверни", "свернуть", "minimize window", "minimize")) {
            log.info("✅ Matched WINDOW_MINIMIZE, correlationId={}", correlationId);
            return intentResult("WINDOW_MINIMIZE", lang, correlationId, Map.of());
        }
        
        if (matchesAny(text, "разверни окно", "разверни", "развернуть", "maximize window", "maximize")) {
            log.info("✅ Matched WINDOW_MAXIMIZE, correlationId={}", correlationId);
            return intentResult("WINDOW_MAXIMIZE", lang, correlationId, Map.of());
        }
        
        if (matchesAny(text, "заблокируй экран", "заблокируй", "блокировка", "lock screen", "lock")) {
            log.info("✅ Matched LOCK_SCREEN, correlationId={}", correlationId);
            return intentResult("LOCK_SCREEN", lang, correlationId, Map.of());
        }

        // ==================== Scenarios / Protocols ====================
        if (matchesAny(text, "рабочий режим", "режим работы", "work mode", "working mode")) {
            log.info("✅ Matched WORK_MODE, correlationId={}", correlationId);
            return intentResult("WORK_MODE", lang, correlationId, Map.of());
        }
        
        if (matchesAny(text, "режим отдыха", "отдых", "отдохнуть", "rest mode", "relax mode", "relaxation")) {
            log.info("✅ Matched REST_MODE, correlationId={}", correlationId);
            return intentResult("REST_MODE", lang, correlationId, Map.of());
        }
        
        if (matchesAny(text, "режим фокусировки", "фокус", "сосредоточиться", "focus mode", "focus")) {
            log.info("✅ Matched FOCUS_MODE, correlationId={}", correlationId);
            return intentResult("FOCUS_MODE", lang, correlationId, Map.of());
        }
        
        if (matchesAny(text, "вечеринка", "время вечеринки", "протокол вечеринка", "party", "house party", "party mode")) {
            log.info("✅ Matched PROTOCOL_HOUSE_PARTY, correlationId={}", correlationId);
            return intentResult("HOUSE_PARTY", lang, correlationId, Map.of());
        }
        
        if (matchesAny(text, "чистый лист", "всё закрой", "закрой всё", "clean slate", "shut down", "clean up")) {
            log.info("✅ Matched PROTOCOL_CLEAN_SLATE, correlationId={}", correlationId);
            return intentResult("CLEAN_SLATE", lang, correlationId, Map.of());
        }

        // ==================== Small Talk / Wake Acknowledgment ====================
        // Simple wake word acknowledgments - just "Jarvis" or similar short phrases
        // These should NOT trigger any PC actions, only return acknowledgment
        if (isSmallTalkJarvis(text)) {
            log.info("✅ Matched SMALL_TALK_JARVIS, correlationId={}", correlationId);
            return intentResult("SMALL_TALK_JARVIS", lang, correlationId, Map.of());
        }
        
        // "Are you there?" style questions
        if (matchesAny(text, "не спишь", "ты тут", "ты здесь", "ты на месте", "ты слышишь",
                "are you there", "are you awake", "you there", "are you listening")) {
            log.info("✅ Matched ARE_YOU_THERE, correlationId={}", correlationId);
            return intentResult("ARE_YOU_THERE", lang, correlationId, Map.of());
        }

        // ==================== Greetings ====================
        if (matchesAny(text, "привет", "здравствуй", "добрый день", "доброе утро", "hello", "hi", "good morning", "hey")) {
            log.info("✅ Matched GREETING, correlationId={}", correlationId);
            return intentResult("GREETING", lang, correlationId, Map.of());
        }
        
        if (matchesAny(text, "пока", "до свидания", "прощай", "goodbye", "bye", "see you")) {
            log.info("✅ Matched GOODBYE, correlationId={}", correlationId);
            return intentResult("GOODBYE", lang, correlationId, Map.of());
        }
        
        if (matchesAny(text, "спасибо", "благодарю", "thank you", "thanks")) {
            log.info("✅ Matched THANKS, correlationId={}", correlationId);
            return intentResult("THANKS", lang, correlationId, Map.of());
        }

        // ==================== Fallback ====================
        log.warn("❓ No intent matched for text='{}', correlationId={}", text, correlationId);
        return IntentResult.builder()
                .handled(false)
                .action("UNKNOWN")
                .correlationId(correlationId)
                .parameters(Map.of())
                // Response will be generated by orchestrator using phrase provider
                .response(null)
                .build();
    }

    private boolean matchesAny(String text, String... patterns) {
        for (String p : patterns) {
            if (text.contains(p)) return true;
        }
        return false;
    }
    
    /**
     * Normalize common STT (Speech-to-Text) misrecognitions.
     * Vosk and other STT engines sometimes mishear similar-sounding words.
     * This method fixes known misrecognitions to improve intent matching.
     */
    private String normalizeSttErrors(String text) {
        return text
            // Common Vosk misrecognitions for Russian
            .replace("влить", "увеличить")     // "влить громкость" → "увеличить громкость"
            .replace("влей", "увеличь")        // "влей громкость" → "увеличь громкость"
            .replace("выть", "")               // noise artifact
            .replace("лить", "")               // partial misrecognition
            // Remove filler words that don't affect intent
            .replaceAll("\\bпожалуйста\\b", "")
            .replaceAll("\\bдавай\\b", "")
            .replaceAll("\\bсейчас\\b", "")
            .replaceAll("\\bну\\b", "")
            .replaceAll("\\bплиз\\b", "")      // "please" in slang
            .replaceAll("\\bplease\\b", "")
            // Normalize multiple spaces
            .replaceAll("\\s+", " ")
            .trim();
    }

    /**
     * Check if the text is a simple wake acknowledgment without a command.
     * Examples: "джарвис", "jarvis", "джарвис ты тут?", "jarvis, are you there?"
     */
    private boolean isSmallTalkJarvis(String text) {
        // Remove common filler words and punctuation
        String cleaned = text.replaceAll("[,?!.]", "").trim();
        
        // Check for simple single-word wake (just "jarvis" / "джарвис")
        if (cleaned.equals("jarvis") || cleaned.equals("джарвис") || 
            cleaned.equals("джарвис ты") || cleaned.equals("jarvis you")) {
            return true;
        }
        
        // Check for very short phrases that are essentially just wake word + filler
        String[] words = cleaned.split("\\s+");
        if (words.length <= 3) {
            // If it's short and contains mainly wake word, it's small talk
            if ((cleaned.startsWith("jarvis") || cleaned.startsWith("джарвис")) &&
                !containsActionKeywords(cleaned)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Check if text contains any action-triggering keywords.
     * Used to distinguish "Jarvis" (small talk) from "Jarvis, louder" (command).
     */
    private boolean containsActionKeywords(String text) {
        String[] actionKeywords = {
            // Volume
            "громче", "тише", "louder", "quieter", "volume", "звук", "mute",
            // Media
            "следующий", "предыдущий", "пауза", "стоп", "играй", "next", "prev", "play", "pause", "stop",
            // Apps
            "открой", "запусти", "open", "launch", "браузер", "browser", "terminal", "youtube",
            // Window
            "сверни", "разверни", "minimize", "maximize", "lock",
            // Scenarios
            "режим", "mode", "вечеринка", "party", "чистый лист", "clean slate"
        };
        
        for (String keyword : actionKeywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Create an IntentResult without pre-generating response text.
     * The actual cinematic response will be generated by the orchestrator
     * using JarvisPhraseProvider.
     */
    private IntentResult intentResult(String action, String lang, String correlationId, Map<String, Object> params) {
        return IntentResult.builder()
                .handled(true)
                .action(action)
                .correlationId(correlationId)
                .parameters(params)
                // Don't set response - let orchestrator generate it with phrase provider
                .response(null)
                .build();
    }
}
