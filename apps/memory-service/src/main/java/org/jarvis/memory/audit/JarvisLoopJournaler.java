package org.jarvis.memory.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.events.AuditEventType;
import org.jarvis.events.EventTopics;
import org.jarvis.events.JarvisEvent;
import org.jarvis.memory.obsidian.ObsidianVaultProperties;
import org.jarvis.memory.obsidian.ObsidianVaultWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * P2 — closes the last leg of the Jarvis Loop: every successful command
 * leaves a markdown footprint in the operator's Obsidian vault under
 * {@code 01_Daily/}. Kafka group is distinct from {@link AuditEventConsumer}'s
 * Postgres projector so both see every audit event.
 *
 * <p>Only {@link AuditEventType#COMMAND_EXECUTED} produces a journal entry
 * for now — failure / expired events stay in the Postgres audit table only.
 * This keeps the daily note clean and matches SPEC-1's "spoken success
 * leaves a written trail" expectation for the demo loop.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "jarvis.memory.loop-journal", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class JarvisLoopJournaler {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ObsidianVaultProperties vaultProperties;
    private final ObsidianVaultWriter vaultWriter;
    private final ObjectMapper objectMapper;

    @Value("${jarvis.memory.loop-journal.zone-id:UTC}")
    private String zoneIdRaw;

    @KafkaListener(
            topics = EventTopics.AUDIT,
            groupId = "${jarvis.memory.loop-journal.consumer-group:obsidian-loop-journal}")
    public void onAuditEvent(@Payload String json,
                             @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key) {
        if (!vaultProperties.isEnabled()) {
            return;
        }
        JarvisEvent event;
        try {
            event = objectMapper.readValue(json, JarvisEvent.class);
        } catch (Exception ex) {
            log.warn("loop-journal got malformed event (key={}): {}", key, ex.getMessage());
            return;
        }
        if (event.getEventType() != AuditEventType.COMMAND_EXECUTED) {
            return;
        }
        if (event.getCommandId() == null || event.getCommandId().isBlank()) {
            log.debug("loop-journal skipping COMMAND_EXECUTED without commandId");
            return;
        }

        ZoneId zone;
        try {
            zone = ZoneId.of(zoneIdRaw);
        } catch (RuntimeException ex) {
            zone = ZoneId.of("UTC");
        }
        ZonedDateTime when = (event.getOccurredAt() == null
                ? java.time.Instant.now()
                : event.getOccurredAt()).atZone(zone);

        String dailySubdir = vaultProperties.getDailySubdir();
        if (dailySubdir == null || dailySubdir.isBlank()) {
            dailySubdir = "01_Daily";
        }
        String day = when.toLocalDate().format(DATE_FMT);
        String slug = sanitize(event.getCommandId());
        String filename = day + "-jarvis-loop-" + slug + ".md";
        Path relative = Paths.get(dailySubdir, filename);

        String body = renderMarkdown(event, when);
        String written = vaultWriter.writeMarkdown(relative, body);
        if (written != null) {
            log.info("loop-journal wrote {} for cmd={} intent={}",
                    written, event.getCommandId(), intentOf(event));
        }
    }

    private String renderMarkdown(JarvisEvent event, ZonedDateTime when) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("type: jarvis-loop\n");
        sb.append("date: ").append(when.toLocalDate().format(DATE_FMT)).append('\n');
        sb.append("time: ").append(when.toLocalTime().format(TIME_FMT)).append('\n');
        sb.append("event_id: ").append(safe(event.getEventId())).append('\n');
        sb.append("command_id: ").append(safe(event.getCommandId())).append('\n');
        sb.append("correlation_id: ").append(safe(event.getTraceId())).append('\n');
        sb.append("user_id: ").append(safe(event.getUserId())).append('\n');
        sb.append("intent: ").append(safe(intentOf(event))).append('\n');
        sb.append("source: ").append(safe(event.getSource())).append('\n');
        sb.append("status: SUCCESS\n");
        sb.append("---\n\n");
        sb.append("# Jarvis Loop — ").append(safe(intentOf(event))).append("\n\n");
        sb.append("- Time: `").append(when.toLocalTime().format(TIME_FMT)).append("`\n");
        sb.append("- Command: `").append(safe(event.getCommandId())).append("`\n");
        sb.append("- User: `").append(safe(event.getUserId())).append("`\n");
        Object transcript = payloadValue(event, "transcript");
        if (transcript != null) {
            sb.append("- Transcript: ").append(transcript).append('\n');
        }
        Object nlp = payloadValue(event, "nlp_intent");
        if (nlp != null) {
            sb.append("- NLP intent: `").append(nlp).append("`\n");
        }
        return sb.toString();
    }

    private static String intentOf(JarvisEvent event) {
        Object v = payloadValue(event, "intent");
        return v == null ? "unknown" : v.toString();
    }

    private static Object payloadValue(JarvisEvent event, String key) {
        Map<String, Object> payload = event.getPayload();
        return payload == null ? null : payload.get(key);
    }

    private static String safe(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String sanitize(String raw) {
        if (raw == null) return "unknown";
        String cleaned = raw.replaceAll("[^A-Za-z0-9._-]+", "-");
        if (cleaned.length() > 40) cleaned = cleaned.substring(0, 40);
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }
}
