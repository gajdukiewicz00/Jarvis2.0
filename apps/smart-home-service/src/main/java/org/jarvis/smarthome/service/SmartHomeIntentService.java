package org.jarvis.smarthome.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.smarthome.model.IntentMatchStatus;
import org.jarvis.smarthome.model.ParsedIntent;
import org.jarvis.smarthome.model.SmartHomeDeviceDefinition;
import org.jarvis.smarthome.model.SmartHomeIntentResolution;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a natural-language utterance (parsed by {@link IntentParser}) to a
 * concrete device from {@link SmartHomeDeviceCatalog} plus the planned action.
 *
 * <p>This is planning only: it never calls {@code SmartHomeService#executeAction}.
 * Real actuation is a separate, explicit step the caller takes after reviewing
 * the plan (actual hardware actuation stays gated behind the configured
 * {@code SmartHomeCommandTransport}).
 */
@Service
@RequiredArgsConstructor
public class SmartHomeIntentService {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");

    private static final double CONFIDENCE_DEVICE_NOT_FOUND = 0.3;
    private static final double CONFIDENCE_DEVICE_AMBIGUOUS = 0.5;

    private final IntentParser intentParser;
    private final SmartHomeDeviceCatalog deviceCatalog;

    /** Parse {@code utterance} and resolve its device reference against the registry. Never actuates hardware. */
    public SmartHomeIntentResolution resolve(String utterance) {
        String text = utterance == null ? "" : utterance.trim();
        ParsedIntent parsed = intentParser.parse(text);

        if (parsed.status() != IntentMatchStatus.RESOLVED) {
            return new SmartHomeIntentResolution(text, parsed.status(), parsed.confidence(),
                    parsed.action(), parsed.payload(), null, List.of(), parsed.message());
        }
        if (parsed.deviceQuery() == null || parsed.deviceQuery().isBlank()) {
            return new SmartHomeIntentResolution(text, IntentMatchStatus.UNKNOWN, parsed.confidence(),
                    parsed.action(), parsed.payload(), null, List.of(),
                    "No device reference found to resolve against the registry");
        }

        List<String> queryTokens = tokenize(parsed.deviceQuery());
        List<ScoredDevice> matches = deviceCatalog.all().stream()
                .map(device -> new ScoredDevice(device, score(device, queryTokens)))
                .filter(scored -> scored.score() > 0)
                .toList();

        if (matches.isEmpty()) {
            return new SmartHomeIntentResolution(text, IntentMatchStatus.UNKNOWN, CONFIDENCE_DEVICE_NOT_FOUND,
                    parsed.action(), parsed.payload(), null, List.of(),
                    "No device in the registry matches: " + parsed.deviceQuery());
        }

        int topScore = matches.stream().mapToInt(ScoredDevice::score).max().orElse(0);
        List<SmartHomeDeviceDefinition> topMatches = matches.stream()
                .filter(scored -> scored.score() == topScore)
                .map(ScoredDevice::device)
                .toList();

        if (topMatches.size() > 1) {
            return new SmartHomeIntentResolution(text, IntentMatchStatus.AMBIGUOUS, CONFIDENCE_DEVICE_AMBIGUOUS,
                    parsed.action(), parsed.payload(), null, topMatches,
                    "Multiple devices match: " + parsed.deviceQuery());
        }

        SmartHomeDeviceDefinition device = topMatches.get(0);
        boolean actionSupported = parsed.action() == null || device.supportedActions().contains(parsed.action());
        String message = actionSupported
                ? "Resolved device and action; not executed (planning only)"
                : "Resolved device, but it does not support action " + parsed.action()
                        + " (supported: " + device.supportedActions() + ")";
        return new SmartHomeIntentResolution(text, IntentMatchStatus.RESOLVED, parsed.confidence(),
                parsed.action(), parsed.payload(), device, List.of(), message);
    }

    private static int score(SmartHomeDeviceDefinition device, List<String> queryTokens) {
        String haystack = (device.id().replace('_', ' ') + " " + device.displayName() + " " + device.room()
                + " " + device.type().name().replace('_', ' ')).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : queryTokens) {
            if (haystack.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private record ScoredDevice(SmartHomeDeviceDefinition device, int score) {
    }
}
