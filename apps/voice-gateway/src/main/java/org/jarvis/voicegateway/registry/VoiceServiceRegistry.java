package org.jarvis.voicegateway.registry;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Central registry of every voice-accessible Jarvis Control Center service. Drives three things:
 * <ol>
 *   <li>the "what can you do" catalog ({@link #catalogText}),</li>
 *   <li>the service-domain guard ({@link #matchDomain}) that forbids a generic-LLM refusal when a
 *       transcript clearly mentions a known local domain,</li>
 *   <li>the availability wording (READY/BETA/MOCK/EXPERIMENTAL/UNAVAILABLE) each service speaks.</li>
 * </ol>
 *
 * <p>The actual phrase → intent matching lives in the rule YAML catalog; this registry is the
 * metadata + safety-net layer so no visible service ever falls through to "I can't do that from
 * chat".
 */
@Component
public class VoiceServiceRegistry {

    public enum Availability { READY, BETA, MOCK, EXPERIMENTAL, UNAVAILABLE }

    /**
     * @param serviceId    stable id (e.g. "finance")
     * @param displayNameRu Russian display name for the catalog
     * @param availability  READY/BETA/MOCK/EXPERIMENTAL/UNAVAILABLE
     * @param keywords      normalized substrings that mean "this domain was mentioned"
     * @param fallbackRu    what to say when a phrase clearly targets this service but no rule fired
     */
    public record ServiceEntry(
            String serviceId,
            String displayNameRu,
            Availability availability,
            List<String> keywords,
            String fallbackRu) {}

    private static final List<ServiceEntry> SERVICES = List.of(
            new ServiceEntry("pc_control", "управление компьютером", Availability.READY,
                    List.of("компьютер", "терминал", "консол", "телеграм", "файл", "vs code", "vscode",
                            "браузер", "окн", "рабочий стол", "скриншот", "приложение", "калькулятор", "запусти"),
                    "Сэр, я управляю компьютером. Что именно открыть или сделать?"),
            new ServiceEntry("media", "медиа", Availability.READY,
                    List.of("пауза", "воспроизвед", "играй", "трек", "песн", "громкост", "звук", "плеер", "музык"),
                    "Сэр, могу управлять воспроизведением и громкостью."),
            new ServiceEntry("planner", "планер", Availability.READY,
                    List.of("план", "задач", "фокус", "дела на"),
                    "Сэр, планер доступен. Спросите про планы или задачи на сегодня."),
            new ServiceEntry("finance", "финансы", Availability.READY,
                    List.of("финанс", "бюджет", "расход", "потратил", "транзакц"),
                    "Сэр, показать финансовую сводку или последние расходы?"),
            new ServiceEntry("finance_review", "проверка транзакций", Availability.READY,
                    List.of("review inbox", "неподтвержд", "черновик", "на проверку"),
                    "Сэр, показать транзакции на проверку?"),
            new ServiceEntry("memory", "память", Availability.READY,
                    List.of("в памяти", "помнишь", "вспомни", "obsidian", "обсидиан", "заметк", "воспоминан"),
                    "Сэр, память доступна. Что найти?"),
            new ServiceEntry("life", "здоровье и wellness", Availability.BETA,
                    List.of("здоровь", "сон ", "wellness", "велнес", "самочувств", "вес "),
                    "Сэр, wellness в бета-режиме, данные могут быть неполными."),
            new ServiceEntry("analytics", "аналитика", Availability.READY,
                    List.of("аналитик", "статистик", "график"),
                    "Сэр, аналитика доступна. Что показать?"),
            new ServiceEntry("insights", "инсайты", Availability.BETA,
                    List.of("инсайт", "выводы", "прогноз", "рекомендац", "что заметил", "что улучшить"),
                    "Сэр, инсайты в бета-режиме."),
            new ServiceEntry("smart_home", "умный дом", Availability.BETA,
                    List.of("умный дом", "умного дома", "умном доме", "умным домом", "свет", "устройств",
                            "температур", "дверь", "термостат"),
                    "Сэр, умный дом в бета-режиме, действия отправляются в mock-провайдер."),
            new ServiceEntry("vision", "компьютерное зрение", Availability.EXPERIMENTAL,
                    List.of("камер", "vision", "распознай", "перед камерой", "что видит"),
                    "Сэр, компьютерное зрение экспериментальное; действия с камерой требуют подтверждения."),
            new ServiceEntry("proactive", "проактивный режим", Availability.EXPERIMENTAL,
                    List.of("proactive", "проактив", "наблюдени"),
                    "Сэр, проактивный режим экспериментальный."),
            new ServiceEntry("security_privacy", "приватность", Availability.READY,
                    List.of("приватност", "privacy", "panic", "паник"),
                    "Сэр, управление приватностью доступно."),
            new ServiceEntry("security_sessions", "безопасность и аудит", Availability.READY,
                    List.of("сесси", "audit", "аудит", "журнал безопасн", "кто вошел", "событ безопасн"),
                    "Сэр, показать сессии или журнал безопасности?"),
            new ServiceEntry("agent", "агенты", Availability.EXPERIMENTAL,
                    List.of("агент", "swarm", "сварм", "coder", "tester"),
                    "Сэр, агенты экспериментальные; реальный запуск требует подтверждения, по умолчанию dry-run."),
            new ServiceEntry("media_jobs", "медиа-задачи", Availability.MOCK,
                    List.of("медиа задач", "субтитр", "дубляж", "обработай видео", "media job"),
                    "Сэр, медиа-задачи сейчас в mock-режиме. Могу создать тестовую задачу."),
            new ServiceEntry("sync", "синхронизация и pairing", Availability.BETA,
                    List.of("синхрониз", "pairing", "пейринг", "подключи телефон", "android sync"),
                    "Сэр, синхронизация в бета-режиме."),
            new ServiceEntry("diagnostics", "диагностика", Availability.READY,
                    List.of("диагностик", "doctor", "что сломано", "health check", "проверь систему", "проверь сервис"),
                    "Сэр, запускаю диагностику."),
            new ServiceEntry("ai_runtime", "AI runtime", Availability.READY,
                    List.of("статус ии", "статус модели", "llm", "gpu", "ai runtime", "какая модель"),
                    "Сэр, проверяю состояние модели."),
            new ServiceEntry("service_status", "статус сервисов", Availability.READY,
                    List.of("статус сервис", "все сервисы", "что упало", "service status", "backend"),
                    "Сэр, проверяю статус сервисов."),
            new ServiceEntry("settings", "настройки", Availability.READY,
                    List.of("настройк", "settings", "endpoint", "язык"),
                    "Сэр, открыть настройки?"),
            new ServiceEntry("voice_control", "голосовое управление", Availability.READY,
                    List.of("статус голоса", "микрофон", "распознавани", "tts", "перезапусти голос", "не слышишь"),
                    "Сэр, проверяю голосовую подсистему."),
            new ServiceEntry("voice_commands", "каталог команд", Availability.READY,
                    List.of("что ты умеешь", "какие команды", "что я могу сказать", "голосовые команды"),
                    "Сэр, вот что я умею."),
            new ServiceEntry("brain", "ИИ-чат", Availability.READY,
                    List.of(),
                    "Сэр, чем помочь?"));

    public List<ServiceEntry> services() {
        return SERVICES;
    }

    public Optional<ServiceEntry> byId(String serviceId) {
        return SERVICES.stream().filter(s -> s.serviceId().equals(serviceId)).findFirst();
    }

    /**
     * Returns the service whose domain a normalized transcript mentions (first/most-specific match),
     * so the caller can avoid a generic-LLM refusal for a known local domain. "brain" (no keywords)
     * is never matched here — it is the explicit chat fallback.
     */
    public Optional<ServiceEntry> matchDomain(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return Optional.empty();
        }
        String t = normalizedText.toLowerCase(Locale.ROOT);
        return SERVICES.stream()
                .filter(s -> s.keywords().stream().anyMatch(t::contains))
                .findFirst();
    }

    /** Grouped catalog for "что ты умеешь". */
    public String catalogText(boolean ru) {
        if (!ru) {
            return "Sir, I can control the PC, media and volume, planner, finances, memory, analytics, "
                    + "the smart home, security and privacy, diagnostics, service status and settings. "
                    + "Just say what you need.";
        }
        return "Сэр, я умею управлять компьютером, медиа и громкостью, планером, финансами и памятью, "
                + "показывать аналитику и инсайты, управлять умным домом, приватностью и безопасностью, "
                + "запускать диагностику, проверять статус сервисов и модели, а также открывать настройки. "
                + "Просто скажите, что нужно.";
    }
}
