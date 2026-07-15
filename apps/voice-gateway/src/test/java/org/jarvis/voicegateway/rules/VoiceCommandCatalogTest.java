package org.jarvis.voicegateway.rules;

import org.jarvis.voicegateway.rules.VoiceCommandCatalog.Action;
import org.jarvis.voicegateway.rules.VoiceCommandCatalog.ActionTarget;
import org.jarvis.voicegateway.rules.VoiceCommandCatalog.Command;
import org.jarvis.voicegateway.rules.VoiceCommandCatalog.Match;
import org.jarvis.voicegateway.rules.VoiceCommandCatalog.Matcher;
import org.jarvis.voicegateway.rules.VoiceCommandCatalog.MatcherType;
import org.jarvis.voicegateway.rules.VoiceCommandCatalog.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit tests for the {@link VoiceCommandCatalog} value models: the enum
 * {@code from(...)} factories, the record compact constructors' null-handling
 * and defensive copies, {@link Response#textFor(String)} locale-fallback chain,
 * {@link Match} delegation, and {@code normalizeLocale}.
 */
class VoiceCommandCatalogTest {

    @Nested
    @DisplayName("MatcherType.from")
    class MatcherTypeFrom {

        @Test
        void nullOrBlankDefaultsToContains() {
            assertEquals(MatcherType.CONTAINS, MatcherType.from(null));
            assertEquals(MatcherType.CONTAINS, MatcherType.from(""));
            assertEquals(MatcherType.CONTAINS, MatcherType.from("   "));
        }

        @Test
        void exactIsParsedCaseInsensitivelyAndTrimmed() {
            assertEquals(MatcherType.EXACT, MatcherType.from("  exact  "));
            assertEquals(MatcherType.EXACT, MatcherType.from("EXACT"));
        }

        @Test
        void aliasSynonymVariantsMapToAlias() {
            assertEquals(MatcherType.ALIAS, MatcherType.from("alias"));
            assertEquals(MatcherType.ALIAS, MatcherType.from("synonym"));
            assertEquals(MatcherType.ALIAS, MatcherType.from("SYNONYMS"));
        }

        @Test
        void regexAndPatternMapToRegex() {
            assertEquals(MatcherType.REGEX, MatcherType.from("regex"));
            assertEquals(MatcherType.REGEX, MatcherType.from("ReGeX"));
            assertEquals(MatcherType.REGEX, MatcherType.from("pattern"));
        }

        @Test
        void unknownValueDefaultsToContains() {
            assertEquals(MatcherType.CONTAINS, MatcherType.from("something-else"));
        }
    }

    @Nested
    @DisplayName("ActionTarget.from")
    class ActionTargetFrom {

        @Test
        void nullOrBlankDefaultsToInternal() {
            assertEquals(ActionTarget.INTERNAL, ActionTarget.from(null));
            assertEquals(ActionTarget.INTERNAL, ActionTarget.from("  "));
        }

        @Test
        void pcControlAliasesMapToPcControl() {
            assertEquals(ActionTarget.PC_CONTROL, ActionTarget.from("pc_control"));
            assertEquals(ActionTarget.PC_CONTROL, ActionTarget.from("pc"));
            assertEquals(ActionTarget.PC_CONTROL, ActionTarget.from("desktop"));
        }

        @Test
        void systemAliasesMapToSystemIncludingHyphenNormalization() {
            assertEquals(ActionTarget.SYSTEM, ActionTarget.from("system"));
            assertEquals(ActionTarget.SYSTEM, ActionTarget.from("system-command"));
        }

        @Test
        void smartHomeAliasesMapToSmartHome() {
            assertEquals(ActionTarget.SMART_HOME, ActionTarget.from("smart-home"));
            assertEquals(ActionTarget.SMART_HOME, ActionTarget.from("smarthome"));
        }

        @Test
        void unknownValueDefaultsToInternal() {
            assertEquals(ActionTarget.INTERNAL, ActionTarget.from("mystery"));
        }
    }

    @Nested
    @DisplayName("Record compact constructors")
    class RecordConstructors {

        @Test
        void commandCopiesMatchersAndNullBecomesEmpty() {
            Command withNull = new Command("id", "desc", true, 5, null, null, null);
            assertTrue(withNull.matchers().isEmpty());

            Command withList = new Command("id", "desc", true, 5,
                    List.of(new Matcher(MatcherType.EXACT, List.of("hi"))), null, null);
            assertEquals(1, withList.matchers().size());
            assertThrows(UnsupportedOperationException.class,
                    () -> withList.matchers().add(new Matcher(MatcherType.EXACT, List.of("x"))));
        }

        @Test
        void matcherDefaultsTypeToContainsAndValuesToEmpty() {
            Matcher matcher = new Matcher(null, null);
            assertEquals(MatcherType.CONTAINS, matcher.type());
            assertTrue(matcher.values().isEmpty());
        }

        @Test
        void matcherCopiesValuesDefensively() {
            Matcher matcher = new Matcher(MatcherType.ALIAS, List.of("a", "b"));
            assertThrows(UnsupportedOperationException.class, () -> matcher.values().add("c"));
        }

        @Test
        void actionDefaultsTargetToInternalAndParamsToEmpty() {
            Action action = new Action(null, "DO_IT", null, null, null);
            assertEquals(ActionTarget.INTERNAL, action.target());
            assertTrue(action.params().isEmpty());
        }

        @Test
        void actionCopiesProvidedParams() {
            Action action = new Action(ActionTarget.SYSTEM, "DO_IT", "dev-1", "payload",
                    Map.of("k", "v"));
            assertEquals("v", action.params().get("k"));
            assertEquals("dev-1", action.deviceId());
            assertEquals("payload", action.payload());
        }
    }

    @Nested
    @DisplayName("Response.textFor")
    class ResponseTextFor {

        @Test
        void emptyTextReturnsNull() {
            Response response = new Response("key", Map.of());
            assertNull(response.textFor("ru"));
        }

        @Test
        void normalizedLocaleHitIsReturned() {
            Response response = new Response("key", Map.of("ru", "Привет", "en", "Hi"));
            assertEquals("Привет", response.textFor("ru-RU"));
            assertEquals("Hi", response.textFor("en-US"));
        }

        @Test
        void rawLocaleHitWhenNormalizedMisses() {
            // normalizeLocale("de-DE") -> "de-de" (not ru/en); the map only has the
            // raw "de-DE" key, so the raw-locale fallback branch is exercised.
            Response response = new Response("key", Map.of("de-DE", "Hallo"));
            assertEquals("Hallo", response.textFor("de-DE"));
        }

        @Test
        void fallsBackToRussianThenEnglish() {
            Response ruOnly = new Response("key", Map.of("ru", "Р"));
            assertEquals("Р", ruOnly.textFor("de-DE"));

            Response enOnly = new Response("key", Map.of("en", "Hi"));
            assertEquals("Hi", enOnly.textFor("de-DE"));
        }

        @Test
        void skipsBlankValuesWhenSelectingLocalized() {
            Map<String, String> text = new LinkedHashMap<>();
            text.put("ru", "   ");
            text.put("en", "Hi");
            Response response = new Response("key", text);
            assertEquals("Hi", response.textFor("ru"));
        }

        @Test
        void fallsBackToFirstNonBlankValueWhenNoNamedLocaleMatches() {
            Map<String, String> text = new LinkedHashMap<>();
            text.put("jp", "  ");
            text.put("kr", "안녕");
            Response response = new Response("key", text);
            assertEquals("안녕", response.textFor("de-DE"));
        }

        @Test
        void returnsNullWhenEveryValueIsBlank() {
            Response response = new Response("key", Map.of("x", "   "));
            assertNull(response.textFor("de-DE"));
        }
    }

    @Nested
    @DisplayName("Match delegation")
    class MatchDelegation {

        @Test
        void delegatesActionResponseKeyAndTextToCommand() {
            Action action = new Action(ActionTarget.PC_CONTROL, "VOLUME_UP", null, null, Map.of());
            Response response = new Response("resp.key", Map.of("ru", "Готово"));
            Command command = new Command("cmd", "desc", true, 1, List.of(), action, response);

            Match match = new Match(command, MatcherType.CONTAINS, "громче", Map.of("delta", 10));

            assertEquals(action, match.action());
            assertEquals("VOLUME_UP", match.actionName());
            assertEquals("resp.key", match.responseKey());
            assertEquals("Готово", match.responseText("ru-RU"));
            assertEquals(10, match.parameters().get("delta"));
        }

        @Test
        void nullActionAndResponseYieldNullDelegates() {
            Command command = new Command("cmd", "desc", true, 1, List.of(), null, null);
            Match match = new Match(command, MatcherType.EXACT, "value", null);

            assertNull(match.action());
            assertNull(match.actionName());
            assertNull(match.responseKey());
            assertNull(match.responseText("ru"));
            assertTrue(match.parameters().isEmpty());
        }
    }

    @Nested
    @DisplayName("normalizeLocale")
    class NormalizeLocale {

        @Test
        void nullOrBlankBecomesRu() {
            assertEquals("ru", VoiceCommandCatalog.normalizeLocale(null));
            assertEquals("ru", VoiceCommandCatalog.normalizeLocale("  "));
        }

        @Test
        void russianAndEnglishPrefixesAreCollapsed() {
            assertEquals("ru", VoiceCommandCatalog.normalizeLocale("ru-RU"));
            assertEquals("en", VoiceCommandCatalog.normalizeLocale("EN-gb"));
        }

        @Test
        void unknownLocaleIsLowercasedButPreserved() {
            assertEquals("fr-fr", VoiceCommandCatalog.normalizeLocale("FR-FR"));
        }
    }
}
