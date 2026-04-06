package org.jarvis.voicegateway.rules;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleBasedVoiceCommandService {

    private final VoiceCommandCatalogLoader catalogLoader;

    private volatile List<CompiledCommand> compiledCommands = List.of();

    @PostConstruct
    public void init() {
        compiledCommands = catalogLoader.commands().stream()
                .filter(VoiceCommandCatalog.Command::enabled)
                .map(this::compile)
                .sorted(Comparator.comparingInt(CompiledCommand::priority).reversed()
                        .thenComparing(CompiledCommand::id))
                .toList();
        log.info("Compiled {} active rule-based voice commands", compiledCommands.size());
    }

    public Optional<VoiceCommandCatalog.Match> match(String text, String locale) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        String normalizedText = normalize(text);
        if (normalizedText.isBlank()) {
            return Optional.empty();
        }
        String boundedText = boundaryWrap(normalizedText);

        MatchCandidate best = null;
        for (CompiledCommand command : compiledCommands) {
            for (CompiledMatcher matcher : command.matchers()) {
                MatchResolution resolution = resolveMatch(
                        normalizedText,
                        boundedText,
                        matcher,
                        command.command().action() != null ? command.command().action().params() : Map.of());
                if (resolution == null) {
                    continue;
                }
                MatchCandidate candidate = new MatchCandidate(
                        command.command(),
                        matcher.type(),
                        resolution.matchedValue(),
                        resolution.parameters(),
                        score(command, matcher));
                if (best == null || candidate.score() > best.score()) {
                    best = candidate;
                }
            }
        }

        if (best == null) {
            return Optional.empty();
        }

        log.info("✅ Rule-based command matched: id={}, action={}, matcherType={}, phrase='{}'",
                best.command().id(),
                best.command().action() != null ? best.command().action().name() : "UNKNOWN",
                best.matcherType(),
                best.matchedValue());

        return Optional.of(new VoiceCommandCatalog.Match(
                best.command(),
                best.matcherType(),
                best.matchedValue(),
                best.parameters()));
    }

    public int getLoadedCommandCount() {
        return compiledCommands.size();
    }

    private CompiledCommand compile(VoiceCommandCatalog.Command command) {
        List<CompiledMatcher> matchers = new ArrayList<>();
        for (VoiceCommandCatalog.Matcher matcher : command.matchers()) {
            for (String value : matcher.values()) {
                String normalizedValue = matcher.type() == VoiceCommandCatalog.MatcherType.REGEX
                        ? normalizeRegex(value)
                        : normalize(value);
                if (normalizedValue.isBlank()) {
                    continue;
                }
                Pattern pattern = matcher.type() == VoiceCommandCatalog.MatcherType.REGEX
                        ? Pattern.compile(normalizedValue, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                        : null;
                matchers.add(new CompiledMatcher(
                        matcher.type(),
                        value,
                        normalizedValue,
                        pattern));
            }
        }
        return new CompiledCommand(command, List.copyOf(matchers));
    }

    private MatchResolution resolveMatch(String normalizedText, String boundedText, CompiledMatcher matcher,
                                         Map<String, Object> actionParameters) {
        return switch (matcher.type()) {
            case EXACT, ALIAS -> normalizedText.equals(matcher.normalizedValue())
                    ? new MatchResolution(matcher.rawValue(), actionParameters)
                    : null;
            case CONTAINS -> boundedText.contains(boundaryWrap(matcher.normalizedValue()))
                    ? new MatchResolution(matcher.rawValue(), actionParameters)
                    : null;
            case REGEX -> resolveRegexMatch(normalizedText, matcher, actionParameters);
        };
    }

    private MatchResolution resolveRegexMatch(String normalizedText, CompiledMatcher matcher,
                                              Map<String, Object> actionParameters) {
        if (matcher.pattern() == null) {
            return null;
        }
        Matcher regexMatcher = matcher.pattern().matcher(normalizedText);
        if (!regexMatcher.find()) {
            return null;
        }
        return new MatchResolution(
                regexMatcher.group(),
                resolveTemplatePlaceholders(actionParameters, regexMatcher));
    }

    private Map<String, Object> resolveTemplatePlaceholders(Map<String, Object> source, Matcher regexMatcher) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            resolved.put(entry.getKey(), resolveTemplateValue(entry.getValue(), regexMatcher));
        }
        return Map.copyOf(resolved);
    }

    private Object resolveTemplateValue(Object value, Matcher regexMatcher) {
        if (value instanceof String text) {
            return substituteGroups(text, regexMatcher);
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() != null) {
                    nested.put(String.valueOf(entry.getKey()), resolveTemplateValue(entry.getValue(), regexMatcher));
                }
            }
            return Map.copyOf(nested);
        }
        if (value instanceof List<?> rawList) {
            List<Object> nested = new ArrayList<>();
            for (Object item : rawList) {
                nested.add(resolveTemplateValue(item, regexMatcher));
            }
            return List.copyOf(nested);
        }
        return value;
    }

    private String substituteGroups(String template, Matcher regexMatcher) {
        String resolved = template;
        for (int index = 0; index <= regexMatcher.groupCount(); index++) {
            String group = regexMatcher.group(index);
            if (group == null) {
                continue;
            }
            resolved = resolved
                    .replace("{" + index + "}", group)
                    .replace("${" + index + "}", group);
        }
        return resolved;
    }

    private int score(CompiledCommand command, CompiledMatcher matcher) {
        int typeScore = switch (matcher.type()) {
            case EXACT -> 4_000;
            case ALIAS -> 3_000;
            case REGEX -> 2_000;
            case CONTAINS -> 1_000;
        };
        return typeScore + matcher.normalizedValue().length() + (command.priority() * 100);
    }

    static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[\\p{Punct}&&[^:/]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String normalizeRegex(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .trim();
    }

    private static String boundaryWrap(String value) {
        return " " + value + " ";
    }

    private record CompiledCommand(VoiceCommandCatalog.Command command, List<CompiledMatcher> matchers) {
        int priority() {
            return command.priority();
        }

        String id() {
            return command.id();
        }
    }

    private record CompiledMatcher(
            VoiceCommandCatalog.MatcherType type,
            String rawValue,
            String normalizedValue,
            Pattern pattern) {
    }

    private record MatchCandidate(
            VoiceCommandCatalog.Command command,
            VoiceCommandCatalog.MatcherType matcherType,
            String matchedValue,
            Map<String, Object> parameters,
            int score) {
    }

    private record MatchResolution(String matchedValue, Map<String, Object> parameters) {
    }
}
