package org.jarvis.voicegateway.rules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared rule-command catalog models.
 */
public final class VoiceCommandCatalog {

    private VoiceCommandCatalog() {
    }

    public enum MatcherType {
        EXACT,
        ALIAS,
        CONTAINS,
        REGEX;

        public static MatcherType from(String rawValue) {
            if (rawValue == null || rawValue.isBlank()) {
                return CONTAINS;
            }
            return switch (rawValue.trim().toUpperCase(Locale.ROOT).replace('-', '_')) {
                case "EXACT" -> EXACT;
                case "ALIAS", "SYNONYM", "SYNONYMS" -> ALIAS;
                case "REGEX", "PATTERN" -> REGEX;
                default -> CONTAINS;
            };
        }
    }

    public enum ActionTarget {
        INTERNAL,
        PC_CONTROL,
        SYSTEM,
        SMART_HOME;

        public static ActionTarget from(String rawValue) {
            if (rawValue == null || rawValue.isBlank()) {
                return INTERNAL;
            }
            return switch (rawValue.trim().toUpperCase(Locale.ROOT).replace('-', '_')) {
                case "PC_CONTROL", "PC", "DESKTOP" -> PC_CONTROL;
                case "SYSTEM", "SYSTEM_COMMAND" -> SYSTEM;
                case "SMART_HOME", "SMARTHOME" -> SMART_HOME;
                default -> INTERNAL;
            };
        }
    }

    public record Command(
            String id,
            String description,
            boolean enabled,
            int priority,
            List<Matcher> matchers,
            Action action,
            Response response) {

        public Command {
            matchers = matchers == null ? List.of() : List.copyOf(matchers);
        }
    }

    public record Matcher(MatcherType type, List<String> values) {

        public Matcher {
            type = type == null ? MatcherType.CONTAINS : type;
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record Action(
            ActionTarget target,
            String name,
            String deviceId,
            Object payload,
            Map<String, Object> params) {

        public Action {
            target = target == null ? ActionTarget.INTERNAL : target;
            params = immutableCopy(params);
        }
    }

    public record Response(String key, Map<String, String> text) {

        public Response {
            text = immutableCopy(text);
        }

        public String textFor(String locale) {
            if (text.isEmpty()) {
                return null;
            }
            String normalizedLocale = normalizeLocale(locale);
            String localized = text.get(normalizedLocale);
            if (localized != null && !localized.isBlank()) {
                return localized;
            }
            if (!normalizedLocale.equals(locale)) {
                localized = text.get(locale);
                if (localized != null && !localized.isBlank()) {
                    return localized;
                }
            }
            localized = text.get("ru");
            if (localized != null && !localized.isBlank()) {
                return localized;
            }
            localized = text.get("en");
            if (localized != null && !localized.isBlank()) {
                return localized;
            }
            return text.values().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElse(null);
        }
    }

    public record Match(
            Command command,
            MatcherType matcherType,
            String matchedValue,
            Map<String, Object> parameters) {

        public Match {
            parameters = immutableCopy(parameters);
        }

        public Action action() {
            return command.action();
        }

        public String actionName() {
            return command.action() != null ? command.action().name() : null;
        }

        public String responseKey() {
            return command.response() != null ? command.response().key() : null;
        }

        public String responseText(String locale) {
            return command.response() != null ? command.response().textFor(locale) : null;
        }
    }

    private static <T> Map<String, T> immutableCopy(Map<String, T> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(source));
    }

    public static String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return "ru";
        }
        String normalized = locale.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("ru")) {
            return "ru";
        }
        if (normalized.startsWith("en")) {
            return "en";
        }
        return normalized;
    }
}
