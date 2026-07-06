package org.jarvis.sync;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * Phase 12 — what the plaintext inside a {@link SyncEnvelope} represents.
 *
 * <p>Add new kinds at the end. Old consumers default unknown kinds to
 * {@link #UNKNOWN} so a newer Android client can ship a kind the local
 * sync-service hasn't shipped support for yet without crashing.</p>
 *
 * <p>{@link #UNKNOWN} is annotated {@link JsonEnumDefaultValue} so Jackson
 * can resolve it as the fallback for unrecognized wire values. This only
 * takes effect on an {@code ObjectMapper} that also has
 * {@code DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE}
 * enabled — callers must configure that feature to get the graceful
 * degradation documented above.</p>
 */
public enum SyncPayloadKind {
    FINANCE_ENTRY,
    COMMAND_INTENT,
    DEVICE_HEARTBEAT,
    HEALTH_ENTRY,
    @JsonEnumDefaultValue
    UNKNOWN
}
