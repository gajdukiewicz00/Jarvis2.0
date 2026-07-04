package org.jarvis.voicegateway.rules;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers VoiceCommandCatalogLoader's YAML parsing and helper-method branches.
 *
 * <p>Most cases here invoke the loader's private parsing helpers directly (via
 * reflection) with hand-built {@link Map}/{@link List} fixtures that mirror what
 * SnakeYAML would hand back for a given YAML document. This exercises malformed/edge
 * input branches without needing to add new classpath YAML fixtures, which would be
 * picked up by every other test that scans {@code classpath*:voice-commands/*.yaml}
 * (including RuleCommandCatalogConsistencyTest) and could break unrelated coverage
 * work happening in parallel.
 */
class VoiceCommandCatalogLoaderTest {

    private final VoiceCommandCatalogLoader loader = new VoiceCommandCatalogLoader();

    // ==================== load() / commands() against the real catalog ====================

    @Test
    void loadPopulatesCommandsFromRealClasspathCatalogAndCommandsIsImmutable() {
        loader.load();

        assertTrue(loader.getLoadedCommandCount() > 0);
        List<VoiceCommandCatalog.Command> commands = loader.commands();
        assertEquals(loader.getLoadedCommandCount(), commands.size());
        assertThrows(UnsupportedOperationException.class, () -> commands.add(null));
    }

    // ==================== loadCommands(Resource) ====================

    @Test
    void loadCommandsReturnsEmptyWhenRootIsNotAMap() {
        Resource resource = yamlResource("- just\n- a\n- list\n");

        assertTrue(invokeLoadCommands(resource).isEmpty());
    }

    @Test
    void loadCommandsReturnsEmptyWhenCommandsKeyIsMissing() {
        Resource resource = yamlResource("other: value\n");

        assertTrue(invokeLoadCommands(resource).isEmpty());
    }

    @Test
    void loadCommandsReturnsEmptyWhenCommandsIsNotAList() {
        Resource resource = yamlResource("commands: not-a-list\n");

        assertTrue(invokeLoadCommands(resource).isEmpty());
    }

    @Test
    void loadCommandsSkipsNonMapEntriesAndParsesValidOnes() {
        String yaml = "commands:\n"
                + "  - \"just a string entry\"\n"
                + "  - id: rule_test\n"
                + "    matchers:\n"
                + "      - type: exact\n"
                + "        values: [\"hello\"]\n"
                + "    action: WAKE_RESPONSE\n";
        Resource resource = yamlResource(yaml);

        List<VoiceCommandCatalog.Command> commands = invokeLoadCommands(resource);

        assertEquals(1, commands.size());
        assertEquals("rule_test", commands.get(0).id());
        assertEquals("WAKE_RESPONSE", commands.get(0).action().name());
    }

    @Test
    void loadCommandsReturnsEmptyWhenResourceIsUnreadable() {
        Resource resource = new ByteArrayResource("commands: []".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("boom");
            }
        };

        assertTrue(invokeLoadCommands(resource).isEmpty());
    }

    @Test
    void loadCommandsReturnsEmptyWhenYamlIsMalformed() {
        // A leading tab is not valid YAML indentation and reliably trips SnakeYAML's
        // scanner, exercising the RuntimeException catch branch (as opposed to the
        // IOException branch exercised by loadCommandsReturnsEmptyWhenResourceIsUnreadable).
        Resource resource = yamlResource("commands:\n\t- bad indentation\n");

        assertTrue(invokeLoadCommands(resource).isEmpty());
    }

    // ==================== parseCommand(Map, String) ====================

    @Test
    void parseCommandReturnsNullWhenMatchersAreMissing() {
        Map<String, Object> commandMap = new LinkedHashMap<>();
        commandMap.put("id", "rule_no_matchers");
        commandMap.put("action", "SOME_ACTION");

        assertNull(invokeParseCommand(commandMap));
    }

    @Test
    void parseCommandReturnsNullWhenIdIsMissing() {
        Map<String, Object> commandMap = validCommandMap();
        commandMap.remove("id");

        assertNull(invokeParseCommand(commandMap));
    }

    @Test
    void parseCommandReturnsNullWhenActionIsMissing() {
        Map<String, Object> commandMap = validCommandMap();
        commandMap.remove("action");

        assertNull(invokeParseCommand(commandMap));
    }

    @Test
    void parseCommandHonorsExplicitEnabledFalseAndPriorityAndDescription() {
        Map<String, Object> commandMap = validCommandMap();
        commandMap.put("enabled", false);
        commandMap.put("priority", 7);
        commandMap.put("description", "Test description");

        VoiceCommandCatalog.Command command = invokeParseCommand(commandMap);

        assertFalse(command.enabled());
        assertEquals(7, command.priority());
        assertEquals("Test description", command.description());
    }

    @Test
    void parseCommandDefaultsEnabledTrueAndPriorityZeroWhenAbsent() {
        VoiceCommandCatalog.Command command = invokeParseCommand(validCommandMap());

        assertTrue(command.enabled());
        assertEquals(0, command.priority());
    }

    @Test
    void parseCommandBuildsActionFromMapWithFullFields() {
        Map<String, Object> commandMap = validCommandMap();
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("target", "smart_home");
        action.put("name", "TURN_ON");
        action.put("deviceId", "kitchen_light");
        action.put("payload", "on");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("brightness", 80);
        action.put("params", params);
        commandMap.put("action", action);

        VoiceCommandCatalog.Command command = invokeParseCommand(commandMap);

        assertEquals(VoiceCommandCatalog.ActionTarget.SMART_HOME, command.action().target());
        assertEquals("TURN_ON", command.action().name());
        assertEquals("kitchen_light", command.action().deviceId());
        assertEquals("on", command.action().payload());
        assertEquals(80, command.action().params().get("brightness"));
    }

    @Test
    void parseCommandParsesResponseKeyAndText() {
        Map<String, Object> commandMap = validCommandMap();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("key", "yes_sir");
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("ru", "Да, сэр.");
        text.put("en", "Yes, sir.");
        response.put("text", text);
        commandMap.put("response", response);

        VoiceCommandCatalog.Command command = invokeParseCommand(commandMap);

        assertEquals("yes_sir", command.response().key());
        assertEquals("Да, сэр.", command.response().text().get("ru"));
    }

    private Map<String, Object> validCommandMap() {
        Map<String, Object> commandMap = new LinkedHashMap<>();
        commandMap.put("id", "rule_valid");
        commandMap.put("action", "SOME_ACTION");
        Map<String, Object> matcher = new LinkedHashMap<>();
        matcher.put("type", "exact");
        matcher.put("values", List.of("hello"));
        commandMap.put("matchers", List.of(matcher));
        return commandMap;
    }

    private VoiceCommandCatalog.Command invokeParseCommand(Map<?, ?> commandMap) {
        return (VoiceCommandCatalog.Command)
                ReflectionTestUtils.invokeMethod(loader, "parseCommand", commandMap, "test.yaml");
    }

    // ==================== parseAction(Object) ====================

    @Test
    void parseActionReturnsInternalActionForPlainString() {
        VoiceCommandCatalog.Action action = invokeParseAction(" WAKE_RESPONSE ");

        assertEquals(VoiceCommandCatalog.ActionTarget.INTERNAL, action.target());
        assertEquals("WAKE_RESPONSE", action.name());
    }

    @Test
    void parseActionReturnsNullForNonStringNonMapValue() {
        assertNull(invokeParseAction(List.of("not", "an", "action")));
    }

    @Test
    void parseActionReturnsNullForNullValue() {
        assertNull(invokeParseAction(null));
    }

    private VoiceCommandCatalog.Action invokeParseAction(Object rawAction) {
        return (VoiceCommandCatalog.Action) ReflectionTestUtils.invokeMethod(loader, "parseAction", rawAction);
    }

    // ==================== parseMatchers(Object) ====================

    @Test
    void parseMatchersReturnsEmptyWhenNotAList() {
        assertTrue(invokeParseMatchers("not-a-list").isEmpty());
    }

    @Test
    void parseMatchersSkipsNonMapEntriesAndEmptyValues() {
        Map<String, Object> validMatcher = new LinkedHashMap<>();
        validMatcher.put("type", "alias");
        validMatcher.put("values", List.of("ok"));
        Map<String, Object> emptyValuesMatcher = new LinkedHashMap<>();
        emptyValuesMatcher.put("type", "exact");
        emptyValuesMatcher.put("values", List.of());

        List<VoiceCommandCatalog.Matcher> matchers =
                invokeParseMatchers(List.of("not-a-map", emptyValuesMatcher, validMatcher));

        assertEquals(1, matchers.size());
        assertEquals(VoiceCommandCatalog.MatcherType.ALIAS, matchers.get(0).type());
    }

    @Test
    void parseMatchersDefaultsMissingTypeToContains() {
        Map<String, Object> matcher = new LinkedHashMap<>();
        matcher.put("values", List.of("ping"));

        List<VoiceCommandCatalog.Matcher> matchers = invokeParseMatchers(List.of(matcher));

        assertEquals(VoiceCommandCatalog.MatcherType.CONTAINS, matchers.get(0).type());
    }

    @SuppressWarnings("unchecked")
    private List<VoiceCommandCatalog.Matcher> invokeParseMatchers(Object rawMatchers) {
        return (List<VoiceCommandCatalog.Matcher>) ReflectionTestUtils.invokeMethod(loader, "parseMatchers", rawMatchers);
    }

    // ==================== parseResponse(Object) ====================

    @Test
    void parseResponseReturnsNullWhenNotAMap() {
        assertNull(invokeParseResponse("not-a-map"));
    }

    @Test
    void parseResponseParsesKeyAndTextMap() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("key", "loading_sir");
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("ru", "Загружаю, сэр.");
        response.put("text", text);

        VoiceCommandCatalog.Response parsed = invokeParseResponse(response);

        assertEquals("loading_sir", parsed.key());
        assertEquals("Загружаю, сэр.", parsed.text().get("ru"));
    }

    private VoiceCommandCatalog.Response invokeParseResponse(Object rawResponse) {
        return (VoiceCommandCatalog.Response) ReflectionTestUtils.invokeMethod(loader, "parseResponse", rawResponse);
    }

    // ==================== static helpers ====================

    @Test
    void booleanValueHandlesNullBooleanAndStringInputs() {
        assertTrue((Boolean) invokeStatic("booleanValue", null, true));
        assertFalse((Boolean) invokeStatic("booleanValue", null, false));
        assertTrue((Boolean) invokeStatic("booleanValue", Boolean.TRUE, false));
        assertFalse((Boolean) invokeStatic("booleanValue", "false", true));
        assertTrue((Boolean) invokeStatic("booleanValue", "true", false));
    }

    @Test
    void intValueHandlesNullNumberStringAndUnparseableInputs() {
        assertEquals(5, invokeStatic("intValue", null, 5));
        assertEquals(3, invokeStatic("intValue", 3, 0));
        assertEquals(9, invokeStatic("intValue", "9", 0));
        assertEquals(-1, invokeStatic("intValue", "not-a-number", -1));
    }

    @Test
    void stringListFiltersNonListAndBlankEntries() {
        assertTrue(((List<?>) invokeStatic("stringList", "not-a-list")).isEmpty());

        List<?> values = (List<?>) invokeStatic("stringList", Arrays.asList("a", null, "  ", "b"));
        assertEquals(List.of("a", "b"), values);
    }

    @Test
    void stringMapFiltersNonMapAndNullEntries() {
        assertTrue(((Map<?, ?>) invokeStatic("stringMap", "not-a-map")).isEmpty());

        Map<Object, Object> raw = new LinkedHashMap<>();
        raw.put("ru", "привет");
        raw.put(null, "ignored-null-key");
        raw.put("en", null);
        Map<?, ?> result = (Map<?, ?>) invokeStatic("stringMap", raw);

        assertEquals(Map.of("ru", "привет"), result);
    }

    @Test
    void objectMapFiltersNonMapAndNullValuesButPreservesObjectTypes() {
        assertTrue(((Map<?, ?>) invokeStatic("objectMap", "not-a-map")).isEmpty());

        Map<Object, Object> raw = new LinkedHashMap<>();
        raw.put("level", 5);
        raw.put("ignored", null);
        Map<?, ?> result = (Map<?, ?>) invokeStatic("objectMap", raw);

        assertEquals(5, result.get("level"));
        assertEquals(1, result.size());
    }

    @Test
    void stringValueTrimsAndTreatsBlankAsNull() {
        assertNull(invokeStatic("stringValue", new Object[] {null}));
        assertNull(invokeStatic("stringValue", "   "));
        assertEquals("hi", invokeStatic("stringValue", "  hi  "));
        assertEquals("42", invokeStatic("stringValue", 42));
    }

    @Test
    void resourceSortKeyFallsBackToDescriptionWhenUrlIsUnavailable() {
        Resource resource = new ByteArrayResource("x".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getDescription() {
                return "byte-array-fallback";
            }
        };

        Object key = ReflectionTestUtils.invokeMethod(VoiceCommandCatalogLoader.class, "resourceSortKey", resource);

        assertEquals("byte-array-fallback", key);
    }

    @Test
    void resourceSortKeyUsesUrlWhenResolvable() {
        Resource resource = new ClassPathResource("voice-commands/smart-home.yaml");

        Object key = ReflectionTestUtils.invokeMethod(VoiceCommandCatalogLoader.class, "resourceSortKey", resource);

        assertTrue(key.toString().contains("smart-home.yaml"));
    }

    private Object invokeStatic(String method, Object... args) {
        return ReflectionTestUtils.invokeMethod(VoiceCommandCatalogLoader.class, method, args);
    }

    @SuppressWarnings("unchecked")
    private List<VoiceCommandCatalog.Command> invokeLoadCommands(Resource resource) {
        return (List<VoiceCommandCatalog.Command>) ReflectionTestUtils.invokeMethod(loader, "loadCommands", resource);
    }

    private static Resource yamlResource(String yaml) {
        return new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8));
    }
}
