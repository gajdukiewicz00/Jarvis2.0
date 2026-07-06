package org.jarvis.syncservice.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Micrometer counters for Android sync ingestion and bank-draft dispatch outcomes
 * (exposed via /actuator/prometheus).
 *
 * <p>{@code sync.bank.drafts}'s {@code confidence} tag is read opportunistically from
 * whatever the FINANCE_ENTRY payload's {@code data} map already carries under a
 * {@code confidence} key (defaulting to {@code "unknown"} when absent) — the actual
 * HIGH/MEDIUM/LOW scoring of a bank-notification draft is a {@code life-tracker}
 * concern (see {@code BankNotificationParser}), not sync-service's; this only
 * observes it in transit. {@code stored} reflects whether sync-service's own
 * downstream dispatch call succeeded, which is the only persistence signal
 * sync-service has visibility into.</p>
 */
@Component
public class SyncMetrics {

    private static final String DIRECTION_INBOUND = "inbound";

    private final MeterRegistry registry;

    public SyncMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One Android to cloud sync blob was processed, tagged by its inbox outcome. */
    public void recordEvent(String status) {
        registry.counter("sync.events", "direction", DIRECTION_INBOUND, "status", status).increment();
    }

    /** A FINANCE_ENTRY (bank draft) payload was dispatched downstream. */
    public void recordBankDraft(String confidence, boolean stored) {
        registry.counter("sync.bank.drafts",
                "confidence", confidence == null ? "unknown" : confidence,
                "stored", Boolean.toString(stored)).increment();
    }

    /** One record was ingested via {@code POST /api/v1/sync/records}, tagged by
     * outcome: {@code stored}, {@code duplicate}, or {@code conflict_resolved}. */
    public void recordRecordIngest(String status) {
        registry.counter("sync.records.ingest", "status", status).increment();
    }
}
