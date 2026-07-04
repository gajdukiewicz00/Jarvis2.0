# ADR-0013: Android client + E2E sync + opaque cloud relay

## Status

Accepted (Phase 12 Pass 1). Pixel polish, pairing UX (QR-from-desktop),
and the Pass 2 server→device outbox are deliberately left for the
operator's iterative tuning on a real device.

## Context

Phases 0–11 turned Jarvis into a stable desktop loop with audited
voice + computer-vision and a JavaFX life-map. SPEC-1 §"Phase 12 —
Android And Cloud Relay Later" demands a mobile client that:

1. works fully offline,
2. lets the user log finance entries and dispatch commands,
3. syncs with the on-prem Jarvis once connectivity is back,
4. uses the public internet only via a relay that **cannot read
   personal data**.

The hard constraints I cannot work around:

* the Android module cannot be built or tested in a tool-driven session
  (no SDK, no device, no emulator),
* the cloud relay cannot be validated end-to-end without renting actual
  cloud k8s,
* but the *data plane and crypto* are JVM-side and fully testable.

So Pass 1 splits into four artifacts that line up with that boundary.

## Decision

### `libs/sync-protocol` (Pure Java, no Spring)

* `SyncEnvelope` — wire DTO with version + `routingId` +
  `senderDeviceId` + `nonceB64` + `ciphertextB64`. Stable wire format,
  ignored unknown fields.
* `SyncPayload` — plaintext DTO with `kind` (FINANCE_ENTRY /
  COMMAND_INTENT / DEVICE_HEARTBEAT / UNKNOWN), `clientNonce`,
  `clientOccurredAt`, opaque `data` map.
* `PairingRequest` / `PairingResponse` — handshake DTOs.
* `crypto.Hkdf` — RFC 5869 HKDF-SHA256, validated against the §A.1
  test vector.
* `crypto.SyncCrypto` — JDK-only X25519 ECDH + Ed25519 sign/verify +
  ChaCha20-Poly1305 AEAD seal/open + HKDF-derived per-device session
  key. **No BouncyCastle**, no native libs — every primitive ships
  with JDK 21.
* `crypto.SessionKeys` — wrapper around the 32-byte session key with a
  `wipe()` for explicit zeroing.

### `apps/sync-service` (Spring Boot, on-prem only)

* Two-step pairing: `POST /api/v1/sync/pairing/init` returns a
  per-pairing fresh server X25519 pubkey + nonce; the server's privkey
  is parked in a TTL'd Caffeine cache. `POST /api/v1/sync/pairing/complete`
  verifies the device's Ed25519 signature over `nonce||deviceKexPub`,
  derives the session key, persists the pairing, audit-emits
  `SYNC_DEVICE_PAIRED`, returns `routingId + senderDeviceId`.
* `POST /api/v1/sync/blobs` validates routing/device/replay, opens
  the AEAD with the per-pairing session key, parses the payload, and
  dispatches:
  - `FINANCE_ENTRY` → `POST life-tracker /api/v1/life/finance/transaction`
  - `COMMAND_INTENT` → `POST orchestrator /api/v1/orchestrator/execute`
    so the **Phase 5 risk classifier + confirmation pipeline** fires
    on mobile commands the same way it does on voice.
  - `DEVICE_HEARTBEAT` → just touches `lastSeen`.
* `ReplayCache` (Caffeine, 24h sliding) — rejects replayed
  `(deviceId, nonce)` pairs even though the AEAD itself doesn't.
* `InMemoryPairingStore` — Pass 1 default; a JPA-backed implementation
  can drop in via `@ConditionalOnMissingBean`.
* `HttpDispatchClient` — fail-soft RestTemplate with 1.5s timeouts;
  surfaces every failure as `SYNC_DISPATCH_FAILED` audit + 502 to the
  device.

### `apps/cloud-relay` (Spring Boot, off-prem)

* Per-`routingId`, per-direction (`TO_HOME` / `TO_DEVICE`) FIFO
  queues, capped at `queue-cap`, blob-TTL'd, idle-TTL'd queue.
* `POST /relay/v1/{routingId}/upstream` and `/downstream` accept
  `application/octet-stream` and store opaque bytes. Reads return
  base64.
* **Structural proof** the relay can't read user data:
  `RelayCannotReadPayloadTest` enumerates the relay classloader for
  any resource under `org.jarvis.sync.*`, `org.jarvis.events.*`,
  `org.jarvis.lifetracker.*`, `org.jarvis.common.eventbus.*`, etc.,
  and fails the build if any are present. The relay's `pom.xml`
  deliberately does **not** depend on `sync-protocol`.

### `apps/android-app` (Gradle / Kotlin / Compose / Room)

* Standalone Gradle project (Maven reactor excludes it — Android isn't
  Maven-friendly).
* Three Compose screens: Manual Finance, Statistics, Commands. Every
  user action lands in Room (`PendingItem`); UI never blocks on the
  network.
* `SyncCryptoKt` — Kotlin port of the JVM `SyncCrypto`, byte-identical
  wire output. Uses Android's built-in providers (Conscrypt) on
  `minSdk 31`.
* `SyncWorker` — WorkManager periodic (15 min, network-required)
  drains the offline queue, seals each item under the per-pairing
  session key, posts as a `SyncEnvelope`. On 2xx → mark synced; on
  failure → mark failed for the next retry.
* `PairingState` — `EncryptedSharedPreferences` for the routingId,
  deviceId, session key, and base URL.

### Acceptance vs polish

The acceptance gate is data-plane + crypto + dispatch + structural
isolation — all JVM-testable. UI polish (charts, theming, the QR
pairing flow) is Pass 2 and needs a real device.

## Consequences

* `life-tracker`, `orchestrator`, `memory-service` and the existing 12
  `life-tracker` tests stay untouched. Phase 12 is strictly additive:
  three new Maven modules + one Gradle subproject + one new k8s
  manifest pair.
* Mobile commands traverse the same orchestrator endpoint as voice
  commands, so the Phase 5 risk gate covers them automatically — there
  is no second classifier to maintain.
* Cloud relay holds **no decryption keys, no Kafka config, no DB
  credentials**. A full compromise of the relay leaks only opaque
  ciphertext that the attacker has no way to decrypt. The
  `RelayCannotReadPayloadTest` makes this property a build-time
  invariant.
* Replay protection is application-level (Caffeine) on top of AEAD
  cryptographic integrity. Pass 2 may move it to Postgres for cluster
  deployments.

## Alternatives considered

* **Bind sync-service into life-tracker.** Rejected — life-tracker
  would absorb pairing, AEAD, and dispatch responsibilities; its
  surface area would balloon. The split keeps each service single-purpose.
* **Use a generic message queue (RabbitMQ) as the cloud relay.**
  Rejected — RabbitMQ is too heavy for an "off-prem dumb pipe", and
  every additional feature is one more thing the cloud could decrypt
  in the future. A 200-line opaque blob queue is exactly what the
  threat model needs.
* **Use libsodium / lazysodium / BouncyCastle.** Rejected — JDK 21
  ships every primitive we need. Fewer moving parts; identical wire
  format on Android (Conscrypt) and JVM (SunEC + SunJCE).
* **Persist activity / pairing in Postgres in Pass 1.** Rejected to
  keep the surface bounded — the in-memory pairing store is enough
  for the diploma demo, with a clean `PairingStore` interface so a JPA
  implementation drops in for Pass 2.
* **LLM-generated sync confirmation messages.** Rejected for the
  acceptance gate; deterministic 2xx/4xx codes are testable.

## References

* SPEC-1 § "Phase 12 — Android And Cloud Relay Later"
* `libs/sync-protocol/`
* `apps/sync-service/`
* `apps/cloud-relay/`
* `apps/android-app/`
* `k8s/base/sync-service/deployment.yaml`
* `k8s/cloud/cloud-relay/deployment.yaml`
* [phase-12-acceptance-evidence.md](../phase-12-acceptance-evidence.md)
