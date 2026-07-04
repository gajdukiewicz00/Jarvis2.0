package org.jarvis.syncservice.dispatch;

import org.jarvis.sync.SyncPayload;

/**
 * Phase 12 — boundary that forwards a decrypted {@link SyncPayload}
 * to the right downstream service.
 *
 * <p>Implementations MUST be fail-soft: a downstream outage should
 * surface as a {@code DispatchResult.failure(...)} so the inbox can
 * audit-emit and return 502 to the device, but the sync-service itself
 * stays up.</p>
 */
public interface DispatchClient {

    DispatchResult dispatchFinanceEntry(String userId, SyncPayload payload);

    DispatchResult dispatchCommandIntent(String userId, SyncPayload payload);

    DispatchResult dispatchHealthEntry(String userId, SyncPayload payload);

    record DispatchResult(boolean ok, String detail) {
        public static DispatchResult success() { return new DispatchResult(true, null); }
        public static DispatchResult failure(String why) { return new DispatchResult(false, why); }
    }
}
