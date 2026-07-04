package org.jarvis.lifetracker.lifemap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Phase 11 — categorises an activity into a {@link TimeCategory} using the
 * regex rule table from {@link LifeMapProperties}.
 *
 * <p>Rules are compiled once per properties change. The classifier checks
 * categories in the configured order (WORK → STUDY → SPORT → SLEEP → REST
 * by default). If nothing matches, returns {@link TimeCategory#CUSTOM}
 * — the panel can show "uncategorised" and the user can teach a rule.</p>
 */
@Slf4j
@Component
public class TimeClassifier {

    private final LifeMapProperties properties;
    private volatile Map<TimeCategory, List<Pattern>> compiledRules;
    private volatile int rulesHash;

    public TimeClassifier(LifeMapProperties properties) {
        this.properties = properties;
        this.compiledRules = compile(properties.getTimeClassification().getRules());
        this.rulesHash = properties.getTimeClassification().getRules().hashCode();
    }

    public TimeCategory classify(String appName, String windowTitle) {
        String haystack = ((appName == null ? "" : appName) + " " +
                (windowTitle == null ? "" : windowTitle)).trim();
        if (haystack.isEmpty()) {
            return TimeCategory.CUSTOM;
        }
        refreshIfChanged();
        for (Map.Entry<TimeCategory, List<Pattern>> entry : compiledRules.entrySet()) {
            for (Pattern p : entry.getValue()) {
                if (p.matcher(haystack).find()) {
                    return entry.getKey();
                }
            }
        }
        return TimeCategory.CUSTOM;
    }

    public TimeCategory classify(String tag) {
        return classify(tag, null);
    }

    private void refreshIfChanged() {
        Map<String, List<String>> current = properties.getTimeClassification().getRules();
        int hash = current.hashCode();
        if (hash != rulesHash) {
            this.compiledRules = compile(current);
            this.rulesHash = hash;
            log.info("TimeClassifier reloaded {} categor{}", compiledRules.size(),
                    compiledRules.size() == 1 ? "y" : "ies");
        }
    }

    private Map<TimeCategory, List<Pattern>> compile(Map<String, List<String>> raw) {
        Map<TimeCategory, List<Pattern>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
            TimeCategory cat = parseCategory(entry.getKey());
            if (cat == null) continue;
            List<Pattern> patterns = entry.getValue().stream()
                    .map(this::compileSafe)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (!patterns.isEmpty()) {
                out.put(cat, patterns);
            }
        }
        return out;
    }

    private Pattern compileSafe(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (Exception ex) {
            log.warn("invalid time-classification regex '{}' — skipped: {}", regex, ex.getMessage());
            return null;
        }
    }

    private TimeCategory parseCategory(String key) {
        if (key == null) return null;
        try {
            return TimeCategory.valueOf(key.toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warn("unknown time category '{}' — ignored", key);
            return null;
        }
    }
}
