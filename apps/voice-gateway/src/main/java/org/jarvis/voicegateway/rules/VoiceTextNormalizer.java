package org.jarvis.voicegateway.rules;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fuzzy STT-variant normalization applied to the recognized text (only) BEFORE rule
 * matching. Vosk/Whisper mangle app names and command phrases ("вес скотт" for "vs code",
 * "телеграмм" for "телеграм", "с кровь все окна" for "сверни все окна"); this canonicalizes
 * those into the phrase a rule actually matches, so known commands route to their tool
 * instead of falling through to generic LLM chat.
 *
 * <p>Applied to the already-{@link RuleBasedVoiceCommandService#normalize normalized} text
 * (lowercase, ё→е, punctuation stripped, spaces collapsed) — never to configured matcher
 * values.
 */
final class VoiceTextNormalizer {

    private VoiceTextNormalizer() {}

    /**
     * Ordered substring aliases (longest key first, applied in order) mapping common STT
     * mistakes to the canonical phrase a rule matches. Keys/values must already be in
     * normalized form (lowercase, no punctuation).
     */
    private static final Map<String, String> ALIASES = buildAliases();

    private static Map<String, String> buildAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        // --- VS Code (STT rarely gets "vs code" right) ---
        aliases.put("визуал студио код", "vs code");
        aliases.put("вижуал студио код", "vs code");
        aliases.put("вес скотт", "vs code");
        aliases.put("вэс скотт", "vs code");
        aliases.put("вес скот", "vs code");
        aliases.put("вес кот", "vs code");
        aliases.put("вэс кот", "vs code");
        aliases.put("вес код", "vs code");
        aliases.put("вэс код", "vs code");
        aliases.put("виз код", "vs code");
        aliases.put("вис код", "vs code");
        aliases.put("вс код", "vs code");
        aliases.put("вskod", "vs code");
        // --- Telegram doubled-letter STT ---
        aliases.put("телеграмму", "телеграм");
        aliases.put("телеграмма", "телеграм");
        aliases.put("телеграмм", "телеграм");
        aliases.put("телегу", "телеграм");
        // --- minimize all windows ("сверни" mangled) ---
        aliases.put("с кровь все окна", "сверни все окна");
        aliases.put("скорой все окна", "сверни все окна");
        aliases.put("с крой все окна", "сверни все окна");
        aliases.put("скрой все окна", "сверни все окна");
        aliases.put("свернуть все окна", "сверни все окна");
        aliases.put("убери все окна", "сверни все окна");
        // --- file manager ---
        aliases.put("файловый менеджер", "файл менеджер");
        return aliases;
    }

    /**
     * Applies the alias substitutions to already-normalized text. Longest aliases first so a
     * more specific phrase wins over a shorter overlapping one.
     */
    static String applyAliases(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return normalized;
        }
        String result = normalized;
        for (Map.Entry<String, String> alias : ALIASES.entrySet()) {
            if (result.contains(alias.getKey())) {
                result = result.replace(alias.getKey(), alias.getValue());
            }
        }
        // Collapse any spaces introduced/left by replacement.
        return result.replaceAll("\\s+", " ").trim();
    }
}
