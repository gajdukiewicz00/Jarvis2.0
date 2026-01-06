package org.jarvis.orchestrator.phrases;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Central provider for Jarvis cinematic phrases in Iron Man style.
 * 
 * <h2>Usage</h2>
 * <pre>{@code
 * String response = phraseProvider.getPhrase(PhraseContext.VOLUME_UP, Language.RU);
 * String greeting = phraseProvider.getPhrase(PhraseContext.MORNING_GREETING, Language.EN, 
 *     Map.of("userName", "Tony", "time", "7:30 AM", "city", "Malibu"));
 * }</pre>
 * 
 * <h2>Adding New Phrases</h2>
 * 1. Add a new {@link PhraseContext} enum value if needed.
 * 2. Add phrase variants to the {@link #initPhrases()} method.
 * 3. Use template placeholders like {time}, {userName}, {city}, etc.
 * 
 * @see PhraseContext
 * @see Language
 */
@Component
public class JarvisPhraseProvider {

    private final Map<PhraseContext, Map<Language, List<String>>> phrases = new EnumMap<>(PhraseContext.class);

    public JarvisPhraseProvider() {
        initPhrases();
    }

    /**
     * Get a random phrase for the given context and language.
     * 
     * @param context The phrase context (intent type)
     * @param language The target language
     * @return A formatted phrase, or a generic fallback if not found
     */
    public String getPhrase(PhraseContext context, Language language) {
        return getPhrase(context, language, Collections.emptyMap());
    }

    /**
     * Get a random phrase for the given context and language, with template substitution.
     * 
     * @param context The phrase context
     * @param language The target language
     * @param params Template parameters like {time}, {userName}, {city}, etc.
     * @return A formatted phrase with substituted parameters
     */
    public String getPhrase(PhraseContext context, Language language, Map<String, Object> params) {
        Map<Language, List<String>> langPhrases = phrases.get(context);
        if (langPhrases == null) {
            return getFallback(language);
        }

        List<String> variants = langPhrases.get(language);
        if (variants == null || variants.isEmpty()) {
            // Try fallback to the other language
            variants = langPhrases.get(language == Language.RU ? Language.EN : Language.RU);
        }
        if (variants == null || variants.isEmpty()) {
            return getFallback(language);
        }

        // Pick a random variant for variety
        String template = variants.get(ThreadLocalRandom.current().nextInt(variants.size()));

        // Substitute parameters
        return substituteParams(template, params);
    }

    /**
     * Get phrase with language auto-detection from transcript text.
     * 
     * @param context The phrase context
     * @param transcriptText The original user command (for language detection)
     * @param params Template parameters
     * @return A formatted phrase in the detected language
     */
    public String getPhraseAuto(PhraseContext context, String transcriptText, Map<String, Object> params) {
        Language lang = LanguageDetector.detect(transcriptText);
        return getPhrase(context, lang, params);
    }

    private String getFallback(Language language) {
        return language == Language.RU ? "Готово, сэр." : "Done, sir.";
    }

    private String substituteParams(String template, Map<String, Object> params) {
        String result = template;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            String value = entry.getValue() != null ? String.valueOf(entry.getValue()) : "";
            result = result.replace(placeholder, value);
        }
        // Remove any remaining unreplaced placeholders
        result = result.replaceAll("\\{[a-zA-Z_]+}", "");
        // Clean up double spaces
        result = result.replaceAll("\\s{2,}", " ").trim();
        return result;
    }

    /**
     * Initialize all phrase variants.
     * This is the central place to add/modify Jarvis responses.
     */
    private void initPhrases() {
        // ==================== Morning Greeting ====================
        add(PhraseContext.MORNING_GREETING, Language.RU,
                "Доброе утро, сэр. Сейчас {time}.",
                "Доброе утро, {userName}. Сейчас {time}. В {city} {temp}°C, {conditions}.",
                "Доброе утро, сэр. Все системы в норме.",
                "Рад вас видеть, сэр. Сегодня {time}."
        );
        add(PhraseContext.MORNING_GREETING, Language.EN,
                "Good morning, sir. It's {time}.",
                "Good morning, sir. It's {time}. The weather in {city} is {temp} degrees with {conditions}.",
                "Good morning, sir. All systems are operational.",
                "Good to see you, sir. It's currently {time}."
        );

        // ==================== System Online ====================
        add(PhraseContext.SYSTEM_ONLINE, Language.RU,
                "Я загружен и готов к работе, сэр.",
                "Все системы онлайн и работают штатно, сэр.",
                "К вашим услугам, сэр. Системы активны.",
                "Я действительно загружен, сэр. Мы онлайн и готовы к работе."
        );
        add(PhraseContext.SYSTEM_ONLINE, Language.EN,
                "I have indeed been uploaded, sir. We're online and ready.",
                "All systems are online and operational, sir.",
                "At your service, sir. Systems are active.",
                "I'm fully loaded and ready to assist, sir."
        );

        // ==================== Welcome Home ====================
        add(PhraseContext.WELCOME_HOME, Language.RU,
                "С возвращением, сэр.",
                "Добро пожаловать домой, сэр.",
                "Рад видеть вас дома, сэр."
        );
        add(PhraseContext.WELCOME_HOME, Language.EN,
                "Welcome home, sir.",
                "Good to have you back, sir.",
                "Welcome back, sir."
        );

        // ==================== Wake Response (just "Jarvis") ====================
        add(PhraseContext.WAKE_RESPONSE, Language.RU,
                "Да, сэр.",
                "Слушаю, сэр.",
                "К вашим услугам, сэр."
        );
        add(PhraseContext.WAKE_RESPONSE, Language.EN,
                "Yes, sir.",
                "At your service, sir.",
                "How may I assist you, sir?"
        );

        // ==================== "Are you there?" / "Не спишь?" ====================
        add(PhraseContext.ARE_YOU_THERE, Language.RU,
                "Для вас, сэр, всегда.",
                "Всегда на связи, сэр.",
                "Я здесь, сэр."
        );
        add(PhraseContext.ARE_YOU_THERE, Language.EN,
                "For you, sir, always.",
                "Always here, sir.",
                "At your service, sir."
        );

        // ==================== Small Talk (just "Jarvis" without command) ====================
        add(PhraseContext.SMALL_TALK_JARVIS, Language.RU,
                "Да, сэр?",
                "Слушаю вас, сэр.",
                "Чем могу помочь, сэр?",
                "К вашим услугам, сэр."
        );
        add(PhraseContext.SMALL_TALK_JARVIS, Language.EN,
                "Yes, sir?",
                "I'm listening, sir.",
                "How may I assist you, sir?",
                "At your service, sir."
        );

        // ==================== Greeting (hello) ====================
        add(PhraseContext.GREETING, Language.RU,
                "К вашим услугам, сэр. Системы активны.",
                "Здравствуйте, сэр. Все системы онлайн.",
                "Добрый день, сэр. Я в вашем распоряжении.",
                "Приветствую, сэр. Чем могу помочь?"
        );
        add(PhraseContext.GREETING, Language.EN,
                "At your service, sir. Systems are online.",
                "Hello, sir. All systems are operational.",
                "Good day, sir. I'm at your disposal.",
                "Greetings, sir. How may I assist you?"
        );

        // ==================== Thanks response ====================
        add(PhraseContext.THANKS, Language.RU,
                "Всегда рад помочь, сэр.",
                "К вашим услугам, сэр.",
                "Обращайтесь, сэр.",
                "Всегда пожалуйста, сэр."
        );
        add(PhraseContext.THANKS, Language.EN,
                "My pleasure, sir.",
                "At your service, sir.",
                "Anytime, sir.",
                "You're welcome, sir."
        );

        // ==================== Generic Acknowledgments ====================
        add(PhraseContext.ACK_START, Language.RU,
                "Немедленно, сэр.",
                "К вашим услугам, сэр.",
                "Понял вас, сэр.",
                "Сию секунду, сэр."
        );
        add(PhraseContext.ACK_START, Language.EN,
                "Right away, sir.",
                "At your service, sir.",
                "Understood, sir.",
                "Very good, sir."
        );

        add(PhraseContext.ACK_SUCCESS, Language.RU,
                "Очень хорошо, сэр.",
                "Выполнено, сэр.",
                "Готово, сэр.",
                "Сделано, сэр."
        );
        add(PhraseContext.ACK_SUCCESS, Language.EN,
                "Very good, sir.",
                "Done, sir.",
                "Complete, sir.",
                "Accomplished, sir."
        );

        add(PhraseContext.ACK_ERROR, Language.RU,
                "К сожалению, не удалось выполнить команду, сэр.",
                "Произошла ошибка, сэр. Попробуйте ещё раз.",
                "Боюсь, что-то пошло не так, сэр."
        );
        add(PhraseContext.ACK_ERROR, Language.EN,
                "I'm afraid that didn't work, sir.",
                "There was an error, sir. Please try again.",
                "Something went wrong, sir."
        );

        add(PhraseContext.ACK_GENERIC, Language.RU,
                "К вашим услугам, сэр.",
                "Слушаю, сэр.",
                "Да, сэр?"
        );
        add(PhraseContext.ACK_GENERIC, Language.EN,
                "At your service, sir.",
                "Yes, sir?",
                "How may I assist you, sir?"
        );

        // ==================== Volume Control ====================
        add(PhraseContext.VOLUME_UP, Language.RU,
                "Громкость увеличена, сэр.",
                "Увеличиваю громкость, сэр."
        );
        add(PhraseContext.VOLUME_UP, Language.EN,
                "Volume increased, sir.",
                "Increasing volume, sir."
        );

        add(PhraseContext.VOLUME_DOWN, Language.RU,
                "Громкость уменьшена, сэр.",
                "Уменьшаю громкость, сэр."
        );
        add(PhraseContext.VOLUME_DOWN, Language.EN,
                "Volume lowered, sir.",
                "Lowering volume, sir."
        );

        add(PhraseContext.VOLUME_MAX, Language.RU,
                "Громкость на максимум, сэр.",
                "Максимальная громкость, сэр.",
                "Звук на полную, сэр."
        );
        add(PhraseContext.VOLUME_MAX, Language.EN,
                "Volume at maximum, sir.",
                "Full volume, sir.",
                "Cranking it up to the max, sir."
        );

        add(PhraseContext.MUTE, Language.RU,
                "Звук выключен, сэр.",
                "Выключаю звук, сэр."
        );
        add(PhraseContext.MUTE, Language.EN,
                "Muted, sir.",
                "Sound muted, sir."
        );

        add(PhraseContext.UNMUTE, Language.RU,
                "Звук включён, сэр.",
                "Включаю звук, сэр."
        );
        add(PhraseContext.UNMUTE, Language.EN,
                "Unmuted, sir.",
                "Sound restored, sir."
        );

        // ==================== Media Control ====================
        add(PhraseContext.PLAY, Language.RU,
                "Воспроизведение, сэр.",
                "Запускаю воспроизведение, сэр.",
                "Играет, сэр."
        );
        add(PhraseContext.PLAY, Language.EN,
                "Playing, sir.",
                "Resuming playback, sir.",
                "Now playing, sir."
        );

        add(PhraseContext.PAUSE, Language.RU,
                "Пауза, сэр.",
                "Ставлю на паузу, сэр.",
                "Приостановлено, сэр."
        );
        add(PhraseContext.PAUSE, Language.EN,
                "Paused, sir.",
                "Playback paused, sir.",
                "On hold, sir."
        );

        add(PhraseContext.MEDIA_TOGGLE, Language.RU,
                "Пауза/воспроизведение, сэр.",
                "Переключаю воспроизведение, сэр."
        );
        add(PhraseContext.MEDIA_TOGGLE, Language.EN,
                "Toggling playback, sir.",
                "Play/pause toggled, sir."
        );

        add(PhraseContext.NEXT_TRACK, Language.RU,
                "Следующий трек, сэр.",
                "Переключаю на следующий, сэр."
        );
        add(PhraseContext.NEXT_TRACK, Language.EN,
                "Next track, sir.",
                "Skipping ahead, sir."
        );

        add(PhraseContext.PREVIOUS_TRACK, Language.RU,
                "Предыдущий трек, сэр.",
                "Возвращаюсь к предыдущему, сэр."
        );
        add(PhraseContext.PREVIOUS_TRACK, Language.EN,
                "Previous track, sir.",
                "Going back, sir."
        );

        // ==================== App Control ====================
        add(PhraseContext.OPEN_APP, Language.RU,
                "Открываю {app}, сэр.",
                "Запускаю {app}, сэр."
        );
        add(PhraseContext.OPEN_APP, Language.EN,
                "Opening {app}, sir.",
                "Launching {app}, sir."
        );

        add(PhraseContext.OPEN_BROWSER, Language.RU,
                "Открываю браузер, сэр.",
                "Запускаю браузер, сэр."
        );
        add(PhraseContext.OPEN_BROWSER, Language.EN,
                "Opening browser, sir.",
                "Launching your browser, sir."
        );

        add(PhraseContext.OPEN_YOUTUBE, Language.RU,
                "Открываю YouTube, сэр.",
                "Запускаю YouTube, сэр."
        );
        add(PhraseContext.OPEN_YOUTUBE, Language.EN,
                "Opening YouTube, sir.",
                "Launching YouTube, sir."
        );

        add(PhraseContext.OPEN_TERMINAL, Language.RU,
                "Открываю терминал, сэр.",
                "Запускаю консоль, сэр."
        );
        add(PhraseContext.OPEN_TERMINAL, Language.EN,
                "Opening terminal, sir.",
                "Launching console, sir."
        );

        add(PhraseContext.OPEN_IDE, Language.RU,
                "Открываю редактор кода, сэр.",
                "Запускаю IDE, сэр."
        );
        add(PhraseContext.OPEN_IDE, Language.EN,
                "Opening your IDE, sir.",
                "Launching code editor, sir."
        );

        // ==================== Window Control ====================
        add(PhraseContext.WINDOW_MINIMIZE, Language.RU,
                "Окно свёрнуто, сэр.",
                "Сворачиваю окно, сэр."
        );
        add(PhraseContext.WINDOW_MINIMIZE, Language.EN,
                "Window minimized, sir.",
                "Minimizing window, sir."
        );

        add(PhraseContext.WINDOW_MAXIMIZE, Language.RU,
                "Окно развёрнуто, сэр.",
                "Разворачиваю окно, сэр."
        );
        add(PhraseContext.WINDOW_MAXIMIZE, Language.EN,
                "Window maximized, sir.",
                "Maximizing window, sir."
        );

        add(PhraseContext.LOCK_SCREEN, Language.RU,
                "Экран заблокирован, сэр.",
                "Блокирую экран, сэр."
        );
        add(PhraseContext.LOCK_SCREEN, Language.EN,
                "Screen locked, sir.",
                "Locking the screen, sir."
        );

        // ==================== Scenarios / Protocols ====================
        add(PhraseContext.WORK_MODE, Language.RU,
                "Активирую рабочий режим, сэр.",
                "Переключаюсь в режим работы, сэр.",
                "Рабочий режим активирован, сэр."
        );
        add(PhraseContext.WORK_MODE, Language.EN,
                "Activating work mode, sir.",
                "Switching to work mode, sir.",
                "Work mode enabled, sir."
        );

        add(PhraseContext.REST_MODE, Language.RU,
                "Активирую режим отдыха, сэр.",
                "Переключаюсь в режим отдыха, сэр.",
                "Приятного отдыха, сэр."
        );
        add(PhraseContext.REST_MODE, Language.EN,
                "Activating rest mode, sir.",
                "Switching to relaxation mode, sir.",
                "Enjoy your rest, sir."
        );

        add(PhraseContext.FOCUS_MODE, Language.RU,
                "Активирую режим фокусировки, сэр.",
                "Режим концентрации включён, сэр.",
                "Все отвлекающие факторы заблокированы, сэр."
        );
        add(PhraseContext.FOCUS_MODE, Language.EN,
                "Activating focus mode, sir.",
                "Focus mode enabled, sir.",
                "All distractions blocked, sir."
        );

        add(PhraseContext.PROTOCOL_HOUSE_PARTY, Language.RU,
                "Протокол «Вечеринка», сэр?",
                "Активирую протокол вечеринки, сэр. Музыка и освещение настроены.",
                "Время праздновать, сэр. Протокол вечеринки запущен."
        );
        add(PhraseContext.PROTOCOL_HOUSE_PARTY, Language.EN,
                "The House Party Protocol, sir?",
                "Activating House Party Protocol, sir. Music and lighting configured.",
                "Time to celebrate, sir. Party protocol engaged."
        );

        add(PhraseContext.PROTOCOL_CLEAN_SLATE, Language.RU,
                "Протокол «Чистый лист», сэр?",
                "Активирую протокол очистки, сэр. Останавливаю все процессы.",
                "Протокол «Чистый лист» запущен. Все неважные сервисы остановлены."
        );
        add(PhraseContext.PROTOCOL_CLEAN_SLATE, Language.EN,
                "The Clean Slate Protocol, sir?",
                "Activating Clean Slate Protocol, sir. Stopping all processes.",
                "Clean Slate Protocol engaged. Non-essential services shut down."
        );

        // ==================== Timer ====================
        add(PhraseContext.TIMER_SET, Language.RU,
                "Таймер установлен на {amount} {unit}, сэр.",
                "Готово, сэр. Напомню через {amount} {unit}."
        );
        add(PhraseContext.TIMER_SET, Language.EN,
                "Timer set for {amount} {unit}, sir.",
                "Done, sir. I'll remind you in {amount} {unit}."
        );

        // ==================== Sarcasm / Personality ====================
        add(PhraseContext.SARCASTIC_FAILURE, Language.RU,
                "Хм, это не сработало и в этот раз, сэр. Удивительно, не правда ли?",
                "Мне кажется, мы уже пробовали это, сэр. С тем же результатом.",
                "Боюсь, повторение не помогло, сэр."
        );
        add(PhraseContext.SARCASTIC_FAILURE, Language.EN,
                "That didn't work again, sir. Shocking, I know.",
                "I believe we've tried this before, sir. With the same result.",
                "Repetition doesn't seem to be helping, sir."
        );

        add(PhraseContext.SECRET_PROJECT, Language.RU,
                "Работаете над секретным проектом, сэр?",
                "Понимаю, сэр. Никаких вопросов.",
                "Как всегда, сэр, огромное удовольствие наблюдать за вашей работой."
        );
        add(PhraseContext.SECRET_PROJECT, Language.EN,
                "Working on a secret project, are we, sir?",
                "Understood, sir. No questions asked.",
                "As always, sir, a great pleasure watching you work."
        );

        add(PhraseContext.SAFETY_BRIEFING, Language.RU,
                "Я подготовил инструктаж по технике безопасности, который вы, вероятно, проигнорируете, сэр.",
                "Напоминаю о мерах предосторожности, сэр. Хотя вы редко к ним прислушиваетесь."
        );
        add(PhraseContext.SAFETY_BRIEFING, Language.EN,
                "I've also prepared a safety briefing for you to entirely ignore, sir.",
                "A reminder about safety protocols, sir. Though you rarely heed them."
        );

        // ==================== Security ====================
        add(PhraseContext.SECURITY_ALERT, Language.RU,
                "Сэр, боюсь, кто-то пытается обойти текущие протоколы безопасности.",
                "Сэр, обнаружена подозрительная активность.",
                "Сэр, кажется, кто-то настойчиво пытается получить доступ."
        );
        add(PhraseContext.SECURITY_ALERT, Language.EN,
                "Sir, I'm afraid my protocols are being overridden.",
                "Sir, suspicious activity detected.",
                "Sir, I'm afraid someone is insisting on unauthorized access."
        );

        // ==================== Life Context ====================
        add(PhraseContext.LIFE_FOOD_TRACKER, Language.RU,
                "Безглютеновые вафли, сэр.",
                "Записал ваш приём пищи, сэр.",
                "Калории учтены, сэр."
        );
        add(PhraseContext.LIFE_FOOD_TRACKER, Language.EN,
                "Gluten-free waffles, sir.",
                "Meal logged, sir.",
                "Calories tracked, sir."
        );

        add(PhraseContext.LIFE_GUESTS, Language.RU,
                "Я продолжу обновлять интерфейс, а вам, сэр, стоит подготовиться к гостям. Приятного вечера.",
                "Напоминаю, сэр, у вас сегодня гости.",
                "Гости прибудут через {time}, сэр."
        );
        add(PhraseContext.LIFE_GUESTS, Language.EN,
                "I'll continue to run variations on the interface, but you should probably prepare for your guests. Enjoy yourself, sir.",
                "A reminder, sir, you have guests coming today.",
                "Guests arriving in {time}, sir."
        );

        add(PhraseContext.HEALTH_STRESS_HINT, Language.RU,
                "Я замечаю признаки сильного напряжения: позднюю активность, много времени за экраном и тревожные заметки. Я не врач, сэр, но, возможно, вам стоит отдохнуть или обсудить это со специалистом.",
                "Сэр, вы работаете уже {hours} часов подряд. Может быть, небольшой перерыв?",
                "Похоже, вы перенапряжены, сэр. Это всего лишь наблюдение, не диагноз."
        );
        add(PhraseContext.HEALTH_STRESS_HINT, Language.EN,
                "I'm noticing signs of high stress: late activity, long screen time, and many anxious notes. I'm not a doctor, sir, but you might want to rest or talk to a professional.",
                "Sir, you've been working for {hours} hours straight. Perhaps a short break?",
                "You appear to be under stress, sir. Just an observation, not a diagnosis."
        );

        // ==================== Fallback ====================
        add(PhraseContext.UNKNOWN_COMMAND, Language.RU,
                "Боюсь, я не совсем расслышал, сэр.",
                "Простите, сэр, я не совсем понял.",
                "Не могли бы вы повторить, сэр?"
        );
        add(PhraseContext.UNKNOWN_COMMAND, Language.EN,
                "I'm afraid I didn't quite catch that, sir.",
                "I beg your pardon, sir, I didn't quite understand.",
                "Could you repeat that, sir?"
        );

        add(PhraseContext.GOODBYE, Language.RU,
                "До свидания, сэр.",
                "Всего доброго, сэр.",
                "До встречи, сэр."
        );
        add(PhraseContext.GOODBYE, Language.EN,
                "Goodbye, sir.",
                "Farewell, sir.",
                "Until next time, sir."
        );

        // ==================== STT Timeout / Noise ====================
        add(PhraseContext.STT_TIMEOUT, Language.RU,
                "Сэр, я вас плохо расслышал.",
                "Простите, сэр, я не расслышал команду.",
                "Боюсь, я не уловил, что вы сказали, сэр."
        );
        add(PhraseContext.STT_TIMEOUT, Language.EN,
                "Sir, I couldn't hear you well.",
                "I'm sorry, sir, I didn't catch that.",
                "I'm afraid I didn't hear what you said, sir."
        );

        add(PhraseContext.STT_NOISE, Language.RU,
                "Слишком много фонового шума, сэр.",
                "Сэр, здесь слишком шумно.",
                "Боюсь, я не могу разобрать вашу команду из-за шума, сэр."
        );
        add(PhraseContext.STT_NOISE, Language.EN,
                "Too much background noise, sir.",
                "Sir, it's too noisy here.",
                "I'm afraid I can't make out your command over the noise, sir."
        );
    }

    private void add(PhraseContext context, Language language, String... variants) {
        phrases.computeIfAbsent(context, k -> new EnumMap<>(Language.class))
                .computeIfAbsent(language, k -> new ArrayList<>())
                .addAll(Arrays.asList(variants));
    }

    // ==================== Convenience Methods ====================

    /**
     * Get current time formatted for greetings.
     */
    public static String getCurrentTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    /**
     * Get greeting phrase with current time.
     */
    public String getMorningGreeting(Language language, String userName) {
        return getPhrase(PhraseContext.MORNING_GREETING, language, Map.of(
                "time", getCurrentTime(),
                "userName", userName != null ? userName : "сэр"
        ));
    }
}

