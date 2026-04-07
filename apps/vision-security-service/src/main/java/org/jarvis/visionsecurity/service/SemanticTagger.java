package org.jarvis.visionsecurity.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SemanticTagger {

    public List<String> deriveTags(String activeWindowTitle, String activeProcessName, String ocrText) {
        String combined = String.join(" ",
                safe(activeWindowTitle),
                safe(activeProcessName),
                safe(ocrText)).toLowerCase(Locale.ROOT);

        Set<String> tags = new LinkedHashSet<>();

        if (containsAny(combined, "code", "intellij", "idea", "terminal", "bash", "pom.xml", "gradle", "github", "git")) {
            tags.add("DEVELOPMENT");
        }
        if (containsAny(combined, "telegram", "discord", "slack", "whatsapp", "signal", "teams", "messages")) {
            tags.add("COMMUNICATION");
        }
        if (containsAny(combined, "gmail", "outlook", "mail", "inbox", "subject:", "compose")) {
            tags.add("EMAIL");
        }
        if (containsAny(combined, "invoice", "payment", "bank", "wallet", "card", "iban", "account balance")) {
            tags.add("FINANCE");
        }
        if (containsAny(combined, "password", "secret", "token", "ssh", "private key", "mnemonic", "api key")) {
            tags.add("SENSITIVE");
        }
        if (containsAny(combined, "spreadsheet", "slides", "document", ".pdf", "word", "excel")) {
            tags.add("DOCUMENTS");
        }
        if (containsAny(combined, "youtube", "netflix", "spotify", "twitch", "steam")) {
            tags.add("MEDIA");
        }

        if (tags.isEmpty()) {
            tags.add("GENERAL_DESKTOP");
        }

        return new ArrayList<>(tags);
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (matchesCandidate(text, candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesCandidate(String text, String candidate) {
        if (candidate.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_')) {
            Pattern pattern = Pattern.compile("(^|[^a-z0-9_])" + Pattern.quote(candidate) + "($|[^a-z0-9_])");
            return pattern.matcher(text).find();
        }
        return text.contains(candidate);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
