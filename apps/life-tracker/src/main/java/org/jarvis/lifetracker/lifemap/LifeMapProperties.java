package org.jarvis.lifetracker.lifemap;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 11 — life-map tuning + classification rules.
 *
 * <p>Pattern lists are regex (case-insensitive) matched against the
 * combined "{appName} {windowTitle}" string. The first matching
 * category wins, with order: WORK → STUDY → SPORT → SLEEP → REST → CUSTOM.</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jarvis.life-map")
public class LifeMapProperties {

    private TimeClassification timeClassification = new TimeClassification();
    private Warnings warnings = new Warnings();

    @Getter @Setter
    public static class TimeClassification {
        /** Map keyed by category name, value = regex patterns. Order is preserved. */
        private Map<String, List<String>> rules = defaultRules();

        private static Map<String, List<String>> defaultRules() {
            Map<String, List<String>> rules = new LinkedHashMap<>();
            rules.put("WORK", List.of(
                    "(?i)intellij|pycharm|webstorm|datagrip|goland",
                    "(?i)vscode|visual\\s*studio|cursor|sublime\\s*text|vim|nvim|emacs",
                    "(?i)terminal|gnome-terminal|konsole|alacritty|kitty",
                    "(?i)slack|microsoft teams|zoom|google meet",
                    "(?i)jira|notion|linear|asana"
            ));
            rules.put("STUDY", List.of(
                    "(?i)coursera|udemy|edx|stepik|stepic|pluralsight",
                    "(?i)stackoverflow|wikipedia|medium\\.com|dev\\.to",
                    "(?i)anki|leetcode|hackerrank|codewars",
                    "(?i)\\.pdf|obsidian|zettelkasten"
            ));
            rules.put("SPORT", List.of(
                    "(?i)strava|garmin|nike\\s*run|adidas\\s*running",
                    "(?i)home\\s*workout|nike\\s*training|fitbit",
                    "(?i)yoga|peloton"
            ));
            rules.put("SLEEP", List.of(
                    "(?i)sleep\\s*cycle|sleep\\s*as\\s*android|autosleep",
                    "(?i)oura|whoop"
            ));
            rules.put("REST", List.of(
                    "(?i)youtube|twitch|netflix|hbo|amazon\\s*prime|kinopoisk",
                    "(?i)spotify|apple\\s*music|yandex\\s*music|tidal|soundcloud",
                    "(?i)telegram|whatsapp|discord|imessage|signal",
                    "(?i)tiktok|instagram|reddit|9gag|twitter|x\\.com|tg",
                    "(?i)steam|epic\\s*games|gog|minecraft|dota|cs:?go|valorant"
            ));
            return rules;
        }
    }

    @Getter @Setter
    public static class Warnings {
        /** Threshold in minutes for daily REST time before TIME_WASTE fires. */
        private int timeWasteMinutesPerDay = 120;
        /** Threshold ratio of expenses to budget before OVERSPEND fires. 0.9 = 90%. */
        private double overspendBudgetRatio = 0.9;
        /** Threshold in hours below which LOW_SLEEP fires for last night. */
        private double lowSleepHours = 6.0;
        /** Set false to disable proactive warnings entirely (e.g. tests / vacation). */
        private boolean enabled = true;
    }
}
