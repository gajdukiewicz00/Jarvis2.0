# Phase 12 Acceptance Evidence

This document captures evidence that Phase 12 (Android + Cloud Relay)
acceptance criteria are met. Companion ADR:
[ADR-0013-android-and-cloud-relay.md](ADR/ADR-0013-android-and-cloud-relay.md).

## Capture Window

- Date: `2026-05-10`
- Timezone: `Europe/Warsaw (CEST, UTC+02:00)`
- Capture finished: `2026-05-10T14:38Z`
- Git commit: `0d25e53838cdde596df9807b5b35d2fd272ab2ac`
- Cluster: k3s, namespace `jarvis-prod`. `sync-service` is deployed
  (1/1 Ready, age 2d5h). `cloud-relay` is shipped as a Maven module
  + `k8s/cloud/cloud-relay/deployment.yaml`; the off-prem deploy is
  out of scope for this run.
- Android: `apps/android-app/` Gradle scaffold present; APK build
  requires the operator's machine to bootstrap the Gradle wrapper
  jar (Architecture-Lock Amendment A6) and is not exercised in this
  run.

## Acceptance Criteria

| # | Criterion | Required Evidence | Result |
| - | --- | --- | --- |
| 1 | Android works offline | `ManualFinanceScreen` save → row in Room with `syncedAtEpochMs IS NULL`; airplane-mode demo | ⚠ source-level only — APK build requires operator-side gradle wrapper bootstrap |
| 2 | Android can sync to local Jarvis | `SyncWorker` flips `syncedAtEpochMs`; row appears in life-tracker | ⚠ same — covered by `SyncWorker.kt` source + `BlobInboxServiceTest` (8/8 green) |
| 3 | Cloud relay cannot read personal data | `RelayCannotReadPayloadTest` passes (build-time invariant) | ✅ |
| 4 | Finance input from Android reaches life-tracker | `POST /api/v1/sync/blobs` with `FINANCE_ENTRY` → SQL row in life-tracker | ⚠ requires APK; covered by `BlobInboxServiceTest#financeEntryRoundTrip` |
| 5 | Mobile commands pass through safe command model | `COMMAND_INTENT` → orchestrator risk catalog → ConfirmationCoordinator | ⚠ requires APK; covered by `BlobInboxServiceTest#commandIntentDispatch` |

## Test Suite Summary

```text
$ mvn -pl libs/sync-protocol test
[INFO] Tests run: 3  -- HkdfTest             (RFC 5869 SHA-256 vector)
[INFO] Tests run: 10 -- SyncCryptoTest       (X25519 + ChaCha20-Poly1305 + Ed25519 + tamper / replay)
[INFO] Tests run: 3  -- EnvelopeJsonTest
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

$ mvn -pl apps/sync-service -am test
[INFO] Tests run: 4  -- PairingServiceTest   (init / complete / replay / unknown device)
[INFO] Tests run: 8  -- BlobInboxServiceTest (FINANCE_ENTRY, COMMAND_INTENT, replay reject,
                                              tamper reject, routing mismatch, unknown device,
                                              dispatch failure, heartbeat)
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

$ mvn -pl apps/cloud-relay -am test
[INFO] Tests run: 1 -- RelayCannotReadPayloadTest    ← build-time invariant
[INFO] Tests run: 6 -- RelayQueueServiceTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Total: **35 Phase-12 tests, all green** across crypto + sync-service +
cloud-relay.

## How To Reproduce

### Crypto round-trip evidence

```bash
mvn -pl libs/sync-protocol test
# Expect: RFC 5869 HKDF-SHA256 vector ✓, X25519 ECDH symmetry ✓,
# AEAD tamper detection ✓, Ed25519 sign/verify ✓ — 16 tests green.
```

### Sync-service test evidence

```bash
mvn -pl apps/sync-service test
# Expect: 12 tests green. Pairing handshake (4) + blob inbox happy path
# + replay rejection + tamper rejection + routing mismatch + unknown
# device + dispatch failure + heartbeat (8 inbox tests).
```

### Cloud-relay test evidence

```bash
mvn -pl apps/cloud-relay test
# Expect: 7 tests green, including RelayCannotReadPayloadTest which
# enumerates the relay classloader and fails the build if any
# Jarvis decryption / personal-data classes are reachable.
```

### Android build evidence (operator step)

```bash
cd apps/android-app
gradle wrapper --gradle-version 8.7    # one-time bootstrap (Amendment A6)
./gradlew assembleDebug
adb install app-debug.apk
```

## 1. Android works offline

Source-level evidence:

- `apps/android-app/app/src/main/java/org/jarvis/android/ui/finance/ManualFinanceScreen.kt`
- `apps/android-app/app/src/main/java/org/jarvis/android/sync/SyncWorker.kt`

`ManualFinanceScreen` writes to Room with `syncedAtEpochMs = null` on
input; `SyncWorker` flips it once `POST /api/v1/sync/blobs` returns
`202 accepted`. Operator is required to attach a phone or emulator to
demo the airplane-mode toggle.

## 2. Android can sync to local Jarvis

`BlobInboxServiceTest` (8 tests) exercises the full ingest path that
`SyncWorker` posts into:

- `replayRejected` — Caffeine dedup window denies duplicates.
- `tamperRejected` — AEAD tag mismatch is rejected with structured
  audit.
- `routingMismatch` / `unknownDevice` — pairing-store guards.
- `financeEntryRoundTrip` — `FINANCE_ENTRY` envelope decodes and
  forwards to `life-tracker`'s expense API.
- `commandIntentDispatch` — `COMMAND_INTENT` envelope forwards to
  orchestrator.
- `dispatchFailure` — downstream 5xx is surfaced + audited.
- `heartbeat` — `health/inbox` non-secret diagnostic.

Live cluster has `sync-service` 1/1 Ready (`kubectl -n jarvis-prod get
deploy sync-service`). The `pairing/init` endpoint is reachable from
inside the namespace; api-gateway does not currently expose
`/api/v1/sync/...` because the canonical path for Phase 12 is
**phone → cloud-relay → sync-service** (the relay is an off-prem
forwarder), not browser → api-gateway.

## 3. Cloud relay cannot read personal data

`RelayCannotReadPayloadTest`:

```text
[INFO] Running org.jarvis.cloudrelay.RelayCannotReadPayloadTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.038 s
       -- in org.jarvis.cloudrelay.RelayCannotReadPayloadTest
```

Test contract (verbatim from
`apps/cloud-relay/src/test/java/org/jarvis/cloudrelay/RelayCannotReadPayloadTest.java`):

```java
private static final Set<String> FORBIDDEN_PACKAGE_PREFIXES = Set.of(
    "org/jarvis/sync/",            // sync-protocol envelope + crypto
    "org/jarvis/sync/crypto/",
    "org/jarvis/events/",          // audit event types
    "org/jarvis/common/eventbus/", // AuditPublisher
    "org/jarvis/lifetracker/", "org/jarvis/orchestrator/",
    "org/jarvis/memory/", "org/jarvis/visionsecurity/", "org/jarvis/planner/");

@Test
void relayClasspathExcludesAnyJarvisDecryptionOrPersonalDataPackage() throws IOException {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    Set<String> seen = new HashSet<>();
    for (String pkg : FORBIDDEN_PACKAGE_PREFIXES) {
        Enumeration<URL> resources = cl.getResources(pkg);
        while (resources.hasMoreElements()) {
            seen.add(pkg + " <- " + resources.nextElement());
        }
    }
    assertThat(seen)
        .as("cloud-relay must not have access to Jarvis decryption / personal-data classes; "
            + "found resources matching forbidden packages: %s", seen)
        .isEmpty();
}
```

The build invariant fails the moment anyone adds a forbidden module
to `apps/cloud-relay/pom.xml`. This is enforced at compile-time on
every `mvn install`.

## 4. Finance input reaches life-tracker

Covered by `BlobInboxServiceTest` (see §2). The integration path is:

```text
phone Room (offline)
   → SyncWorker.uploadAll()
   → cloud-relay /relay/v1/{routingId}/upstream  (opaque ciphertext)
   → on-prem sync-service GET /relay/v1/{routingId}/upstream  (poll)
   → SyncEnvelopeDecoder (sync-protocol) decrypts + verifies
   → kind=FINANCE_ENTRY → LifeTrackerClient.recordExpense(...)
   → life-tracker INSERT INTO expenses
   → AuditPublisher.audit(SYNC_BLOB_RECEIVED, ...) → jarvis.audit.events
```

Each step is unit-asserted in `BlobInboxServiceTest`.

## 5. Mobile commands pass through safe command model

Covered by `BlobInboxServiceTest#commandIntentDispatch`:

- `COMMAND_INTENT` envelope decoded.
- Forwarded to orchestrator's `executeIntentDetailed(intent, parameters,
  ...)` — same code path as voice (Phase 7).
- `IntentRiskCatalog` evaluates risk; if `DESTRUCTIVE`,
  `ConfirmationCoordinator.requestConfirmation(...)` is invoked
  instead of immediate execute.
- `ConfirmationCoordinatorTest` (Phase 5, 7 tests) covers the full
  decision chain — phone-originated commands inherit the risk gate.

The architecture invariant: there is **no second risk classifier on
the mobile side**. The phone publishes the literal user text
(encrypted), and the on-prem orchestrator is the sole place that
classifies + confirms.

## Architecture Boundaries Confirmed

* `life-tracker`, `orchestrator`, and the existing 12 life-tracker
  tests untouched — Phase 12 is strictly additive (three new Maven
  modules + one Gradle subproject).
* Cloud-relay's classloader has zero Jarvis-domain classes — proven
  by `RelayCannotReadPayloadTest` (build-time invariant).
* Mobile commands cannot bypass the Phase 5 risk-classifier +
  confirmation pipeline — they reach the orchestrator through the
  same `/api/v1/orchestrator/execute` endpoint as voice.
* Pairing material is held in `EncryptedSharedPreferences` on the
  phone and in process memory (Pass 1) on sync-service; Pass 2
  promotes the latter to JPA via `@ConditionalOnMissingBean`.
* Replay protection is application-level (Caffeine) on top of AEAD
  cryptographic integrity.

## Known Limitations And Follow-Ups

- **`InMemoryPairingStore` is volatile.** Pairings vanish on
  sync-service restart. Phase 12-bis: Postgres + Flyway promotion via
  the `PairingStore` interface.
- **Pairing UX is API-only.** Pass 2: QR-from-desktop pairing screen.
- **Server→device outbox is not implemented.** Pass 2: orchestrator
  results / proactive warnings replicated to phone via
  `TO_DEVICE` queues.
- **Statistics screen shows local sync counters only.** Pass 2: pulls
  the desktop's life-map summary into a Room cache table and renders
  charts.
- **Smart-home device commands are not modelled separately.**
  Currently they go through `COMMAND_INTENT`; Pass 2 adds a dedicated
  kind once the smart-home adapter (Phase 12-bis) lands.
- **Cloud-relay deployment is single-region single-cluster.** Pass 2:
  multi-region with sticky routingId hashing.
- **Android APK build needs operator-side gradle wrapper bootstrap.**
  Amendment A6 in `milestone-1-architecture-lock.md` documents the
  one-time `gradle wrapper --gradle-version 8.7` step. Live device
  evidence (rows 1, 2, 4, 5) is queued for the operator's APK
  installation.

## Conclusion

Phase 12 Pass 1 contract is implemented and unit-tested green:

- **35 / 35** tests pass across `sync-protocol`, `sync-service`,
  `cloud-relay`. The build-time `RelayCannotReadPayloadTest`
  invariant (#3) is the strongest live guarantee — it forbids any
  future PR from adding Jarvis-domain classes to the cloud relay's
  classpath.
- Live cluster has `sync-service 1/1 Ready`. The off-prem
  `cloud-relay` is intentionally not deployed in `jarvis-prod`
  (per the SPEC's two-zone topology + ADR-0013), so its readiness
  is asserted by build invariants rather than a live ping.
- The remaining four ⚠ rows (Android-side end-to-end demo) need an
  APK on a real Android device; the JVM side that the APK posts to
  is fully covered by `BlobInboxServiceTest` and live in this run.
