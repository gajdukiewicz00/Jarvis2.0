package org.jarvis.visionsecurity.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticTaggerTest {

    private final SemanticTagger semanticTagger = new SemanticTagger();

    @Test
    void derivesMultipleStableTagsFromWindowProcessAndOcrText() {
        assertThat(semanticTagger.deriveTags(
                "IntelliJ IDEA - payments",
                "telegram-desktop",
                "api key invoice bank account password"
        )).containsExactly(
                "DEVELOPMENT",
                "COMMUNICATION",
                "FINANCE",
                "SENSITIVE"
        );
    }

    @Test
    void fallsBackToGeneralDesktopWhenNoRulesMatch() {
        assertThat(semanticTagger.deriveTags(
                "Photos",
                "eog",
                "family picture from the weekend"
        )).containsExactly("GENERAL_DESKTOP");
    }

    @Test
    void doesNotDuplicateTagsWhenSignalsRepeatAcrossInputs() {
        assertThat(semanticTagger.deriveTags(
                "Gmail Inbox",
                "gmail",
                "mail subject: report from outlook inbox"
        )).containsExactly("EMAIL");
    }
}
