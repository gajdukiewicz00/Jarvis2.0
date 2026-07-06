package org.jarvis.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnvelopeJsonTest {

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void envelopeRoundTrip() throws Exception {
        SyncEnvelope e = new SyncEnvelope(
                SyncEnvelope.CURRENT_VERSION,
                "route-1", "dev-1234",
                "AAA", "BBB",
                Instant.parse("2026-05-01T10:00:00Z"));
        String s = json.writeValueAsString(e);
        SyncEnvelope back = json.readValue(s, SyncEnvelope.class);
        assertThat(back.getRoutingId()).isEqualTo("route-1");
        assertThat(back.getSenderDeviceId()).isEqualTo("dev-1234");
        assertThat(back.getNonceB64()).isEqualTo("AAA");
        assertThat(back.getCiphertextB64()).isEqualTo("BBB");
        assertThat(back.getOccurredAtClient()).isEqualTo(Instant.parse("2026-05-01T10:00:00Z"));
    }

    @Test
    void payloadDefaultsUnknownKindToUnknownEnumValue() throws Exception {
        String s = "{\"kind\":\"NONEXISTENT_KIND\",\"clientNonce\":\"n1\",\"data\":{\"k\":\"v\"}}";
        // Jackson by default fails on unknown enum value — test that we *don't* round-trip
        // a typo: the writer side never produces unknown values, the reader must be
        // configured to accept them only when the consumer explicitly chooses to.
        ObjectMapper lenient = new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true);
        SyncPayload p = lenient.readValue(s, SyncPayload.class);
        assertThat(p.getKind()).isEqualTo(SyncPayloadKind.UNKNOWN);
        assertThat(p.getClientNonce()).isEqualTo("n1");
    }

    @Test
    void unrecognizedKindResolvesToUnknownViaJsonEnumDefaultValue() throws Exception {
        // Documented forward-compat guarantee (SyncPayloadKind.java javadoc): a newer client
        // shipping a kind this build hasn't heard of must degrade to UNKNOWN, not blow up.
        // This only works if UNKNOWN carries @JsonEnumDefaultValue *and* the mapper enables
        // READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE — exercise exactly that combination.
        String s = "{\"kind\":\"LOCATION_PING\",\"clientNonce\":\"n2\",\"data\":{\"k\":\"v\"}}";
        ObjectMapper defaultValueMapper = new ObjectMapper()
                .configure(
                        com.fasterxml.jackson.databind.DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE,
                        true);

        SyncPayload p = defaultValueMapper.readValue(s, SyncPayload.class);

        assertThat(p.getKind()).isEqualTo(SyncPayloadKind.UNKNOWN);
        assertThat(p.getClientNonce()).isEqualTo("n2");
    }

    @Test
    void payloadRoundTripPreservesMap() throws Exception {
        SyncPayload p = new SyncPayload(SyncPayloadKind.FINANCE_ENTRY, "n1",
                Instant.parse("2026-05-01T08:00:00Z"),
                Map.of("amount", 12.5, "currency", "EUR", "category", "coffee"));
        String s = json.writeValueAsString(p);
        SyncPayload back = json.readValue(s, SyncPayload.class);
        assertThat(back.getKind()).isEqualTo(SyncPayloadKind.FINANCE_ENTRY);
        assertThat(back.getData()).containsEntry("currency", "EUR");
    }
}
