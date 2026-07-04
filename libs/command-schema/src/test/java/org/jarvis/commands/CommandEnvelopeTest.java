package org.jarvis.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandEnvelopeTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void factoryProducesCommandWithRequiredFields() {
        CommandEnvelope cmd = CommandFactory.create(
                "user-1",
                CommandSource.VOICE,
                "pc.window.focus",
                RiskLevel.LOW,
                Map.of("window", "Firefox"),
                Duration.ofSeconds(30),
                null);

        assertThat(cmd.getCommandId()).startsWith("cmd-");
        assertThat(cmd.getCorrelationId()).startsWith("cmd-");
        assertThat(cmd.getUserId()).isEqualTo("user-1");
        assertThat(cmd.getSource()).isEqualTo(CommandSource.VOICE);
        assertThat(cmd.getIntent()).isEqualTo("pc.window.focus");
        assertThat(cmd.getRiskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(cmd.isRequiresConfirmation()).isFalse();
        assertThat(cmd.getStatus()).isEqualTo(CommandStatus.CREATED);
        assertThat(cmd.getCreatedAt()).isNotNull();
        assertThat(cmd.getExpiresAt()).isAfter(cmd.getCreatedAt());
        assertThat(cmd.getPayload()).containsEntry("window", "Firefox");
    }

    @Test
    void mediumRiskFlagsConfirmationRequired() {
        CommandEnvelope cmd = CommandFactory.create(
                "owner",
                CommandSource.VOICE,
                "fs.delete-file",
                RiskLevel.HIGH,
                Map.of("path", "/tmp/x"),
                Duration.ofSeconds(30),
                null);

        assertThat(cmd.isRequiresConfirmation()).isTrue();
    }

    @Test
    void factoryRejectsMissingFields() {
        assertThatThrownBy(() -> CommandFactory.create(
                null, CommandSource.VOICE, "x", RiskLevel.SAFE,
                Map.of(), Duration.ofSeconds(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");

        assertThatThrownBy(() -> CommandFactory.create(
                "u", null, "x", RiskLevel.SAFE,
                Map.of(), Duration.ofSeconds(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");

        assertThatThrownBy(() -> CommandFactory.create(
                "u", CommandSource.VOICE, " ", RiskLevel.SAFE,
                Map.of(), Duration.ofSeconds(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intent");

        assertThatThrownBy(() -> CommandFactory.create(
                "u", CommandSource.VOICE, "x", RiskLevel.SAFE,
                Map.of(), Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl");
    }

    @Test
    void isExpiredFollowsExpiresAt() {
        Instant now = Instant.now();
        CommandEnvelope cmd = CommandEnvelope.builder()
                .commandId("cmd-1")
                .expiresAt(now.minusSeconds(1))
                .build();

        assertThat(cmd.isExpired(now)).isTrue();
        assertThat(cmd.isExpired(now.minusSeconds(60))).isFalse();
    }

    @Test
    void serialisesAndDeserialisesViaJackson() throws Exception {
        CommandEnvelope cmd = CommandFactory.create(
                "user-1", CommandSource.DESKTOP_UI, "home.light.on",
                RiskLevel.SAFE, Map.of("room", "kitchen"),
                Duration.ofSeconds(60), "trace-1");

        String json = mapper.writeValueAsString(cmd);
        assertThat(json).contains("\"commandId\"")
                .contains("\"intent\":\"home.light.on\"")
                .contains("\"riskLevel\":\"SAFE\"");

        CommandEnvelope back = mapper.readValue(json, CommandEnvelope.class);
        assertThat(back).isEqualTo(cmd);
        assertThat(back.getRiskLevel()).isEqualTo(RiskLevel.SAFE);
        assertThat(back.getPayload()).containsEntry("room", "kitchen");
    }

    @Test
    void resultFactorySetsStatusAndTimestamp() {
        CommandResult ok = CommandResult.success("cmd-1", "trace-1",
                Map.of("exit", 0), 12);
        assertThat(ok.getStatus()).isEqualTo(CommandStatus.SUCCESS);
        assertThat(ok.getCompletedAt()).isNotNull();
        assertThat(ok.getDurationMillis()).isEqualTo(12);

        CommandResult bad = CommandResult.failed("cmd-2", "trace-2",
                "boom", 5);
        assertThat(bad.getStatus()).isEqualTo(CommandStatus.FAILED);
        assertThat(bad.getErrorReason()).isEqualTo("boom");

        CommandResult exp = CommandResult.expired("cmd-3", "trace-3", "ttl");
        assertThat(exp.getStatus()).isEqualTo(CommandStatus.EXPIRED);
    }
}
