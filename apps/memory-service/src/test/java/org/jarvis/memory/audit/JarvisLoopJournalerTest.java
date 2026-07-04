package org.jarvis.memory.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jarvis.events.AuditEventType;
import org.jarvis.events.EventCategory;
import org.jarvis.events.EventSeverity;
import org.jarvis.events.JarvisEvent;
import org.jarvis.memory.obsidian.ObsidianMarkdownRenderer;
import org.jarvis.memory.obsidian.ObsidianVaultProperties;
import org.jarvis.memory.obsidian.ObsidianVaultWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JarvisLoopJournalerTest {

    @TempDir
    Path tempDir;

    private ObsidianVaultProperties properties;
    private ObsidianVaultWriter writer;
    private ObjectMapper mapper;
    private JarvisLoopJournaler journaler;

    @BeforeEach
    void setUp() {
        properties = new ObsidianVaultProperties();
        properties.setEnabled(true);
        properties.setVaultPath(tempDir.toString());
        properties.setDailySubdir("01_Daily");
        writer = new ObsidianVaultWriter(properties, new ObsidianMarkdownRenderer());
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        journaler = new JarvisLoopJournaler(properties, writer, mapper);
        ReflectionTestUtils.setField(journaler, "zoneIdRaw", "UTC");
    }

    private String serialise(JarvisEvent event) throws Exception {
        return mapper.writeValueAsString(event);
    }

    private JarvisEvent commandExecuted(String commandId, String intent, Map<String, Object> extra) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("intent", intent);
        if (extra != null) payload.putAll(extra);
        return JarvisEvent.builder()
                .eventId("evt-1")
                .eventType(AuditEventType.COMMAND_EXECUTED)
                .category(EventCategory.AUDIT)
                .severity(EventSeverity.INFO)
                .source("orchestrator")
                .traceId("corr-1")
                .userId("owner")
                .commandId(commandId)
                .occurredAt(Instant.parse("2026-05-08T14:25:30Z"))
                .payload(payload)
                .build();
    }

    @Test
    void writesDailyMarkdownForCommandExecuted() throws Exception {
        var event = commandExecuted("cmd-abc", "OPEN_URL",
                Map.of("nlp_intent", "open_browser", "transcript", "открой браузер"));

        journaler.onAuditEvent(serialise(event), "cmd-abc");

        Path expected = tempDir.resolve("01_Daily/2026-05-08-jarvis-loop-cmd-abc.md");
        assertThat(Files.exists(expected)).isTrue();
        String content = Files.readString(expected);
        assertThat(content)
                .contains("type: jarvis-loop")
                .contains("command_id: cmd-abc")
                .contains("intent: OPEN_URL")
                .contains("status: SUCCESS")
                .contains("NLP intent: `open_browser`")
                .contains("Transcript: открой браузер");
    }

    @Test
    void ignoresNonExecutedAuditEvents() throws Exception {
        var event = JarvisEvent.builder()
                .eventId("evt-2")
                .eventType(AuditEventType.COMMAND_QUEUED)
                .category(EventCategory.AUDIT)
                .severity(EventSeverity.INFO)
                .source("orchestrator")
                .commandId("cmd-q")
                .occurredAt(Instant.parse("2026-05-08T14:25:30Z"))
                .payload(Map.of("intent", "OPEN_URL"))
                .build();

        journaler.onAuditEvent(serialise(event), "cmd-q");

        Path daily = tempDir.resolve("01_Daily");
        assertThat(Files.notExists(daily) || Files.list(daily).findAny().isEmpty()).isTrue();
    }

    @Test
    void noOpWhenVaultDisabled() throws Exception {
        properties.setEnabled(false);
        var event = commandExecuted("cmd-x", "OPEN_URL", Map.of());

        journaler.onAuditEvent(serialise(event), "cmd-x");

        assertThat(Files.notExists(tempDir.resolve("01_Daily"))).isTrue();
    }

    @Test
    void malformedJsonIsIgnored() {
        journaler.onAuditEvent("{not-json", "key");
        assertThat(Files.notExists(tempDir.resolve("01_Daily"))).isTrue();
    }

    @Test
    void sanitisesCommandIdInFilename() throws Exception {
        var event = commandExecuted("cmd/with bad chars*?", "OPEN_URL", Map.of());

        journaler.onAuditEvent(serialise(event), "cmd-bad");

        try (var stream = Files.list(tempDir.resolve("01_Daily"))) {
            var file = stream.findFirst().orElseThrow();
            assertThat(file.getFileName().toString())
                    .startsWith("2026-05-08-jarvis-loop-cmd-with-bad-chars-")
                    .endsWith(".md");
        }
    }
}
