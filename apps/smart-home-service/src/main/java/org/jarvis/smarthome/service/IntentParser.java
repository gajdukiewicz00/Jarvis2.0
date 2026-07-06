package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.IntentMatchStatus;
import org.jarvis.smarthome.model.ParsedIntent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Rule-based natural-language intent parser for smart-home commands.
 *
 * <p>Understands a small, fixed vocabulary of English and Russian action
 * verbs (e.g. "turn on" / "включи") plus an optional numeric or word payload
 * for parametrized actions (temperature, brightness, color). It does NOT
 * resolve which device the utterance refers to against the device registry
 * — it only extracts the free-text device reference span ({@code deviceQuery})
 * so a caller (see {@link SmartHomeIntentService}) can match it against
 * {@link SmartHomeDeviceCatalog}.
 *
 * <p>This is intentionally a lightweight keyword/stem matcher, not a full NLU
 * pipeline: trigger words ending in {@code *} are matched as prefixes so a
 * single stem covers common Russian verb conjugations (e.g. {@code включ*}
 * matches "включи", "включить", "включим").
 */
@Component
public class IntentParser {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(?:[.,]\\d+)?");
    private static final Pattern PURE_NUMBER_TOKEN = Pattern.compile("\\d+");

    private static final double CONFIDENCE_FULL = 0.9;
    private static final double CONFIDENCE_MISSING_PAYLOAD = 0.7;
    private static final double CONFIDENCE_MISSING_DEVICE_QUERY = 0.55;
    private static final double CONFIDENCE_AMBIGUOUS = 0.3;
    private static final double CONFIDENCE_UNKNOWN = 0.0;

    private static final Set<String> PAYLOAD_ACTIONS = Set.of("SET_TEMPERATURE", "SET_BRIGHTNESS", "SET_COLOR");
    private static final Set<String> NUMERIC_PAYLOAD_ACTIONS = Set.of("SET_TEMPERATURE", "SET_BRIGHTNESS");

    /** Words dropped from the extracted device reference; also used to skip prepositions before a color payload. */
    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "to", "please", "set", "it", "its", "on", "off", "at", "for", "and",
            "поставь", "установи", "пожалуйста", "и", "на", "в", "к", "до", "для", "это");

    /** Russian noun stems translated to the English words used in the device registry (id/name/room). */
    private static final Map<String, String> RU_DEVICE_SYNONYMS = Map.ofEntries(
            Map.entry("кухн", "kitchen"),
            Map.entry("гостин", "living room"),
            Map.entry("спальн", "bedroom"),
            Map.entry("офис", "office"),
            Map.entry("кабинет", "office"),
            Map.entry("прихож", "entrance"),
            Map.entry("коридор", "hallway"),
            Map.entry("холл", "hall"),
            Map.entry("двер", "door"),
            Map.entry("входн", "front"),
            Map.entry("замк", "lock"),
            Map.entry("замок", "lock"),
            Map.entry("свет", "light"),
            Map.entry("ламп", "lamp"),
            Map.entry("термостат", "thermostat"));

    private static final List<ActionRule> ACTION_RULES = List.of(
            rule("SET_TEMPERATURE", "degree", "degrees", "температур*", "градус*"),
            rule("SET_BRIGHTNESS", "brightness", "яркост*"),
            rule("SET_COLOR", "color", "colour", "цвет*"),
            rule("TURN_ON", "turn on", "switch on", "power on", "включ*"),
            rule("TURN_OFF", "turn off", "switch off", "power off", "выключ*"),
            rule("TOGGLE", "toggle", "переключ*"),
            rule("LOCK", "lock", "закро*", "запри*", "заблокир*"),
            rule("UNLOCK", "unlock", "открой*", "отомкн*", "разблокир*"),
            rule("DIM", "dim", "lower", "убав*", "приглуш*", "потускн*"),
            rule("BRIGHTEN", "brighten", "raise", "прибав*", "ярче"));

    /** Parse a raw utterance into a normalized action, free-text device query and optional payload. */
    public ParsedIntent parse(String utterance) {
        if (utterance == null || utterance.isBlank()) {
            return new ParsedIntent(IntentMatchStatus.UNKNOWN, null, null, null, CONFIDENCE_UNKNOWN,
                    "Empty utterance");
        }

        String normalized = utterance.toLowerCase(Locale.ROOT);
        List<String> tokens = tokenize(normalized);

        List<ActionRule> matched = ACTION_RULES.stream().filter(rule -> !rule.firstMatch(tokens).isEmpty()).toList();

        if (matched.isEmpty()) {
            return new ParsedIntent(IntentMatchStatus.UNKNOWN, null, null, null, CONFIDENCE_UNKNOWN,
                    "No recognizable smart-home action in utterance");
        }
        if (matched.size() > 1) {
            String actionNames = matched.stream().map(ActionRule::action).collect(Collectors.joining(", "));
            return new ParsedIntent(IntentMatchStatus.AMBIGUOUS, null, null, null, CONFIDENCE_AMBIGUOUS,
                    "Multiple conflicting actions recognized: " + actionNames);
        }

        ActionRule rule = matched.get(0);
        Set<Integer> consumed = new LinkedHashSet<>(rule.firstMatch(tokens));

        PayloadResult payloadResult = extractPayload(rule.action(), normalized, tokens, consumed);
        if (payloadResult.consumedIndex() >= 0) {
            consumed.add(payloadResult.consumedIndex());
        }

        String deviceQuery = buildDeviceQuery(tokens, consumed);
        return buildResult(rule.action(), deviceQuery, payloadResult.value());
    }

    private ParsedIntent buildResult(String action, String deviceQuery, String payload) {
        boolean needsPayload = PAYLOAD_ACTIONS.contains(action);
        boolean hasPayload = payload != null && !payload.isBlank();
        boolean hasDeviceQuery = deviceQuery != null && !deviceQuery.isBlank();

        if (needsPayload && !hasPayload) {
            return new ParsedIntent(IntentMatchStatus.RESOLVED, action, deviceQuery, null,
                    CONFIDENCE_MISSING_PAYLOAD, "Action recognized but required payload (value) is missing");
        }
        if (!hasDeviceQuery) {
            return new ParsedIntent(IntentMatchStatus.RESOLVED, action, null, payload,
                    CONFIDENCE_MISSING_DEVICE_QUERY, "Action recognized but no device reference found in utterance");
        }
        return new ParsedIntent(IntentMatchStatus.RESOLVED, action, deviceQuery, payload, CONFIDENCE_FULL,
                "Action and device reference recognized");
    }

    private PayloadResult extractPayload(String action, String normalizedUtterance, List<String> tokens, Set<Integer> consumed) {
        if (NUMERIC_PAYLOAD_ACTIONS.contains(action)) {
            return extractNumberNearestTrigger(normalizedUtterance, consumed);
        }
        if ("SET_COLOR".equals(action)) {
            int afterTrigger = consumed.isEmpty() ? -1 : Collections.max(consumed) + 1;
            int idx = afterTrigger;
            if (idx >= 0 && idx < tokens.size() && STOPWORDS.contains(tokens.get(idx))) {
                idx++;
            }
            if (idx >= 0 && idx < tokens.size() && !STOPWORDS.contains(tokens.get(idx))) {
                return new PayloadResult(tokens.get(idx), idx);
            }
            return new PayloadResult(null, -1);
        }
        return new PayloadResult(null, -1);
    }

    /**
     * Among every numeric match in {@code normalizedUtterance}, returns the one whose character span
     * is closest to the trigger token span (e.g. "degrees"/"градуса"), rather than simply the first
     * number occurring anywhere in the string. This matters because the numeric value conventionally
     * sits immediately next to its unit word (e.g. "22 degrees") while an unrelated earlier digit
     * (e.g. a room/device number) can otherwise be mistaken for the payload.
     */
    private PayloadResult extractNumberNearestTrigger(String normalizedUtterance, Set<Integer> consumed) {
        List<int[]> spans = tokenSpans(normalizedUtterance);
        if (consumed.isEmpty()) {
            return new PayloadResult(null, -1);
        }
        int triggerStart = spans.get(Collections.min(consumed))[0];
        int triggerEnd = spans.get(Collections.max(consumed))[1];

        Matcher matcher = NUMBER_PATTERN.matcher(normalizedUtterance);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        while (matcher.find()) {
            int candidateStart = matcher.start();
            int candidateEnd = matcher.end();
            int distance = candidateStart >= triggerEnd
                    ? candidateStart - triggerEnd
                    : triggerStart - candidateEnd;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = matcher.group();
            }
        }
        return best == null ? new PayloadResult(null, -1) : new PayloadResult(best.replace(',', '.'), -1);
    }

    /** Character [start, end) span of every token in {@code text}, in the same order as {@link #tokenize}. */
    private static List<int[]> tokenSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            spans.add(new int[] {matcher.start(), matcher.end()});
        }
        return spans;
    }

    private String buildDeviceQuery(List<String> tokens, Set<Integer> consumed) {
        List<String> remaining = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (consumed.contains(i)) {
                continue;
            }
            String token = tokens.get(i);
            if (STOPWORDS.contains(token) || PURE_NUMBER_TOKEN.matcher(token).matches()) {
                continue;
            }
            remaining.add(translate(token));
        }
        return String.join(" ", remaining).trim();
    }

    private static String translate(String token) {
        for (Map.Entry<String, String> entry : RU_DEVICE_SYNONYMS.entrySet()) {
            if (token.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return token;
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static ActionRule rule(String action, String... phrases) {
        List<String[]> parsed = Arrays.stream(phrases).map(p -> p.split(" ")).toList();
        return new ActionRule(action, parsed);
    }

    /** A single numeric or word payload extracted from the utterance, plus the token index it consumed (-1 if none/not tracked). */
    private record PayloadResult(String value, int consumedIndex) {
    }

    /** An action name plus its EN/RU trigger phrases (each phrase is one or more words; a trailing {@code *} matches as a stem). */
    private record ActionRule(String action, List<String[]> phrases) {

        /** Token indices of the first matching phrase, or empty if none of this rule's phrases occur in {@code tokens}. */
        List<Integer> firstMatch(List<String> tokens) {
            for (String[] phrase : phrases) {
                List<Integer> match = matchPhrase(tokens, phrase);
                if (!match.isEmpty()) {
                    return match;
                }
            }
            return List.of();
        }

        private static List<Integer> matchPhrase(List<String> tokens, String[] phrase) {
            for (int start = 0; start <= tokens.size() - phrase.length; start++) {
                List<Integer> indices = new ArrayList<>();
                boolean matches = true;
                for (int offset = 0; offset < phrase.length; offset++) {
                    String token = tokens.get(start + offset);
                    String word = phrase[offset];
                    boolean hit = word.endsWith("*")
                            ? token.startsWith(word.substring(0, word.length() - 1))
                            : token.equals(word);
                    if (!hit) {
                        matches = false;
                        break;
                    }
                    indices.add(start + offset);
                }
                if (matches) {
                    return indices;
                }
            }
            return List.of();
        }
    }
}
