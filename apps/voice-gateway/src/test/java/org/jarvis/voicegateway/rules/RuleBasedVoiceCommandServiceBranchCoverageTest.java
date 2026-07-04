package org.jarvis.voicegateway.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Supplements {@link RuleBasedVoiceCommandServiceTest} with branches driven by
 * synthetic catalog data rather than the production YAML catalog: blank/empty
 * input short-circuits, null-action parameter resolution, matcher values that
 * normalize away to nothing, best-match scoring across matcher types, and
 * nested regex template placeholder substitution (map/list payloads).
 */
class RuleBasedVoiceCommandServiceBranchCoverageTest {

    @Test
    void matchReturnsEmptyForNullText() {
        RuleBasedVoiceCommandService service = serviceFor(List.of());
        assertTrue(service.match(null, "ru-RU").isEmpty());
    }

    @Test
    void matchReturnsEmptyForBlankText() {
        RuleBasedVoiceCommandService service = serviceFor(List.of());
        assertTrue(service.match("   ", "ru-RU").isEmpty());
    }

    @Test
    void matchReturnsEmptyWhenNormalizedTextBecomesBlank() {
        // Normalization strips punctuation; a text made only of punctuation
        // normalizes down to an empty string and must short-circuit.
        RuleBasedVoiceCommandService service = serviceFor(List.of());
        assertTrue(service.match("!!! ??? ...", "ru-RU").isEmpty());
    }

    @Test
    void matchReturnsEmptyWhenNoCommandsCompiled() {
        RuleBasedVoiceCommandService service = serviceFor(List.of());
        assertTrue(service.match("привет", "ru-RU").isEmpty());
    }

    @Test
    void matcherValuesThatNormalizeToBlankAreSkippedWithoutCrashing() {
        VoiceCommandCatalog.Command command = command(
                "blank-matcher",
                0,
                new VoiceCommandCatalog.Matcher(VoiceCommandCatalog.MatcherType.CONTAINS, List.of("   ", "!!!")),
                action(VoiceCommandCatalog.ActionTarget.INTERNAL, "NOOP", Map.of()));
        RuleBasedVoiceCommandService service = serviceFor(List.of(command));

        Optional<VoiceCommandCatalog.Match> match = service.match("привет мир", "ru-RU");

        assertTrue(match.isEmpty());
        assertEquals(1, service.getLoadedCommandCount());
    }

    @Test
    void matchWithNullActionResolvesEmptyParametersInsteadOfThrowing() {
        VoiceCommandCatalog.Command command = command(
                "no-action",
                0,
                new VoiceCommandCatalog.Matcher(VoiceCommandCatalog.MatcherType.EXACT, List.of("пинг")),
                null);
        RuleBasedVoiceCommandService service = serviceFor(List.of(command));

        Optional<VoiceCommandCatalog.Match> match = service.match("пинг", "ru-RU");

        assertTrue(match.isPresent());
        assertTrue(match.get().parameters().isEmpty());
        assertNull(match.get().actionName());
    }

    @Test
    void exactMatchWinsOverContainsMatchForSameText() {
        VoiceCommandCatalog.Command containsCommand = command(
                "contains-cmd",
                0,
                new VoiceCommandCatalog.Matcher(VoiceCommandCatalog.MatcherType.CONTAINS, List.of("привет")),
                action(VoiceCommandCatalog.ActionTarget.INTERNAL, "CONTAINS_ACTION", Map.of()));
        VoiceCommandCatalog.Command exactCommand = command(
                "exact-cmd",
                0,
                new VoiceCommandCatalog.Matcher(VoiceCommandCatalog.MatcherType.EXACT, List.of("привет")),
                action(VoiceCommandCatalog.ActionTarget.INTERNAL, "EXACT_ACTION", Map.of()));
        RuleBasedVoiceCommandService service = serviceFor(List.of(containsCommand, exactCommand));

        Optional<VoiceCommandCatalog.Match> match = service.match("привет", "ru-RU");

        assertTrue(match.isPresent());
        assertEquals("EXACT_ACTION", match.get().actionName());
        assertEquals(VoiceCommandCatalog.MatcherType.EXACT, match.get().matcherType());
    }

    @Test
    void aliasMatcherMatchesNormalizedWholeText() {
        VoiceCommandCatalog.Command command = command(
                "alias-cmd",
                0,
                new VoiceCommandCatalog.Matcher(VoiceCommandCatalog.MatcherType.ALIAS, List.of("гугл")),
                action(VoiceCommandCatalog.ActionTarget.INTERNAL, "OPEN_URL", Map.of("url", "https://google.com")));
        RuleBasedVoiceCommandService service = serviceFor(List.of(command));

        Optional<VoiceCommandCatalog.Match> match = service.match("Гугл!!!", "ru-RU");

        assertTrue(match.isPresent());
        assertEquals(VoiceCommandCatalog.MatcherType.ALIAS, match.get().matcherType());
        assertEquals("https://google.com", match.get().parameters().get("url"));
    }

    @Test
    void aliasMatcherDoesNotMatchWhenTextHasExtraWords() {
        VoiceCommandCatalog.Command command = command(
                "alias-cmd",
                0,
                new VoiceCommandCatalog.Matcher(VoiceCommandCatalog.MatcherType.ALIAS, List.of("гугл")),
                action(VoiceCommandCatalog.ActionTarget.INTERNAL, "OPEN_URL", Map.of()));
        RuleBasedVoiceCommandService service = serviceFor(List.of(command));

        Optional<VoiceCommandCatalog.Match> match = service.match("открой гугл пожалуйста", "ru-RU");

        assertTrue(match.isEmpty());
    }

    @Test
    void regexMatcherSubstitutesNestedMapAndListPlaceholders() {
        Map<String, Object> nestedMap = Map.of("note", "value-{1}");
        Map<String, Object> params = Map.of(
                "level", "{1}",
                "meta", nestedMap,
                "items", List.of("item-{1}", 5));
        VoiceCommandCatalog.Command command = command(
                "regex-cmd",
                0,
                new VoiceCommandCatalog.Matcher(VoiceCommandCatalog.MatcherType.REGEX, List.of("уровень (\\d+)")),
                action(VoiceCommandCatalog.ActionTarget.INTERNAL, "SET_LEVEL", params));
        RuleBasedVoiceCommandService service = serviceFor(List.of(command));

        Optional<VoiceCommandCatalog.Match> match = service.match("установи уровень 42 пожалуйста", "ru-RU");

        assertTrue(match.isPresent());
        Map<String, Object> resolved = match.get().parameters();
        assertEquals("42", resolved.get("level"));
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) resolved.get("meta");
        assertEquals("value-42", meta.get("note"));
        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) resolved.get("items");
        assertEquals("item-42", items.get(0));
        assertEquals(5, items.get(1));
    }

    @Test
    void regexMatcherWithNoMatchReturnsEmpty() {
        VoiceCommandCatalog.Command command = command(
                "regex-cmd",
                0,
                new VoiceCommandCatalog.Matcher(VoiceCommandCatalog.MatcherType.REGEX, List.of("уровень (\\d+)")),
                action(VoiceCommandCatalog.ActionTarget.INTERNAL, "SET_LEVEL", Map.of("level", "{1}")));
        RuleBasedVoiceCommandService service = serviceFor(List.of(command));

        Optional<VoiceCommandCatalog.Match> match = service.match("совсем другая фраза", "ru-RU");

        assertFalse(match.isPresent());
    }

    @Test
    void higherPriorityCommandWinsOverLowerPriorityWithEqualMatcherType() {
        VoiceCommandCatalog.Command lowPriority = command(
                "low",
                0,
                new VoiceCommandCatalog.Matcher(VoiceCommandCatalog.MatcherType.CONTAINS, List.of("свет")),
                action(VoiceCommandCatalog.ActionTarget.INTERNAL, "LOW_ACTION", Map.of()));
        VoiceCommandCatalog.Command highPriority = command(
                "high",
                10,
                new VoiceCommandCatalog.Matcher(VoiceCommandCatalog.MatcherType.CONTAINS, List.of("свет")),
                action(VoiceCommandCatalog.ActionTarget.INTERNAL, "HIGH_ACTION", Map.of()));
        RuleBasedVoiceCommandService service = serviceFor(List.of(lowPriority, highPriority));

        Optional<VoiceCommandCatalog.Match> match = service.match("включи свет", "ru-RU");

        assertTrue(match.isPresent());
        assertEquals("HIGH_ACTION", match.get().actionName());
    }

    // ==================== Test Fixtures ====================

    private RuleBasedVoiceCommandService serviceFor(List<VoiceCommandCatalog.Command> commands) {
        VoiceCommandCatalogLoader loader = new VoiceCommandCatalogLoader() {
            @Override
            public List<VoiceCommandCatalog.Command> commands() {
                return commands;
            }
        };
        RuleBasedVoiceCommandService service = new RuleBasedVoiceCommandService(loader);
        service.init();
        return service;
    }

    private VoiceCommandCatalog.Command command(String id, int priority, VoiceCommandCatalog.Matcher matcher,
                                                 VoiceCommandCatalog.Action action) {
        return new VoiceCommandCatalog.Command(
                id,
                "test command " + id,
                true,
                priority,
                List.of(matcher),
                action,
                null);
    }

    private VoiceCommandCatalog.Action action(VoiceCommandCatalog.ActionTarget target, String name,
                                               Map<String, Object> params) {
        return new VoiceCommandCatalog.Action(target, name, null, null, params);
    }
}
