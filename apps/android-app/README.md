# Jarvis Android — Phase 12 scaffold

Diploma-grade Android client for Jarvis. **Pass 1 deliverable: the wire
+ DB + UI skeleton; pixel polish is operator-iterated on a real device.**

## What's here

```
app/
├── build.gradle.kts              # Compose / Room / OkHttp / kotlinx.serialization
└── src/main/
    ├── AndroidManifest.xml
    ├── res/values/{strings,themes}.xml
    └── java/org/jarvis/android/
        ├── JarvisApp.kt          # WorkManager periodic sync (15 min)
        ├── MainActivity.kt       # 3-tab Scaffold
        ├── data/
        │   ├── crypto/SyncCryptoKt.kt   # Kotlin port of JVM SyncCrypto
        │   ├── local/JarvisDatabase.kt  # Room — offline-first queue
        │   ├── local/PendingItem.kt
        │   ├── local/PendingItemDao.kt
        │   └── remote/SyncClient.kt     # OkHttp speaking sync-service wire
        ├── sync/
        │   ├── PairingState.kt   # EncryptedSharedPreferences for session key
        │   └── SyncWorker.kt     # drains queue → seals AEAD → POSTs envelope
        └── ui/
            ├── finance/ManualFinanceScreen.kt
            ├── statistics/StatisticsScreen.kt
            └── commands/CommandScreen.kt
```

## How to build

```bash
# Requires Android Studio Hedgehog+ (or AGP 8.5) and Android SDK 34.
cd apps/android-app
# One-time bootstrap of the Gradle wrapper jar (see gradle/wrapper/README.md):
gradle wrapper --gradle-version 8.7
# Then:
./gradlew assembleDebug
```

The Maven reactor at the repo root deliberately excludes this module —
Android is Gradle-only.

> Status: Phase 12 Pass 1 **scaffold**. The wire format, crypto, and
> sync flow are exercised on the JVM side (`mvn -pl libs/sync-protocol,apps/sync-service test`),
> but the APK has not been built or installed by the diploma agent —
> APK bring-up + on-device pairing flow are operator validation work.

## Pairing flow (one-time)

`PairingState.save(...)` is called once after a manual pairing. The user
opens a "Pair with Jarvis" screen on the desktop (Phase 12 Pass 2),
enters the displayed pairing nonce on the phone, and the phone runs:

```kotlin
val client = SyncClient(baseUrl = "http://<lan-host>:8093")
val init = client.pairingInit()
val identity = SyncCryptoKt.generateIdentityKeyPair()
val kex = SyncCryptoKt.generateKexKeyPair()
val identityPub = SyncCryptoKt.encodeEd25519Pub(identity.public)
val kexPub = SyncCryptoKt.encodeX25519Pub(kex.public)
val message = (init.pairingNonceB64 + SyncCryptoKt.b64(kexPub)).toByteArray()
val sig = SyncCryptoKt.signEd25519(identity.private, message)
val resp = client.pairingComplete(SyncClient.PairingRequest(
    deviceLabel = android.os.Build.MODEL,
    identityPubB64 = SyncCryptoKt.b64(identityPub),
    kexPubB64 = SyncCryptoKt.b64(kexPub),
    pairingNonceB64 = init.pairingNonceB64,
    identitySigB64 = SyncCryptoKt.b64(sig)
))
val shared = SyncCryptoKt.x25519Agree(kex.private, SyncCryptoKt.decodeX25519Pub(SyncCryptoKt.unb64(resp.serverKexPubB64)))
val sessionKey = SyncCryptoKt.deriveSessionKey(shared, kexPub, SyncCryptoKt.unb64(resp.serverKexPubB64))
PairingState(this).save(resp.routingId, resp.senderDeviceId, SyncCryptoKt.b64(sessionKey), baseUrl)
```

(A pairing screen with QR-code-from-desktop ingestion is Pass 2 work.)

## Acceptance properties

| # | Property | Where it lives |
| - | --- | --- |
| 1 | Works offline | `PendingItemDao.upsert` is non-blocking; UI never hits the network |
| 2 | Syncs to local Jarvis | `SyncWorker.doWork()` → `SyncClient.postBlob()` |
| 3 | E2E encrypted | `SyncCryptoKt.seal(...)` before any HTTP exit |
| 4 | Finance lands in life-tracker | `ManualFinanceScreen` → `kind=FINANCE_ENTRY` → sync-service dispatches to `/api/v1/life/finance/transaction` |
| 5 | Commands go through safe model | `CommandScreen` → `kind=COMMAND_INTENT` → sync-service dispatches to `orchestrator/execute` (Phase 5 risk gate fires before execution) |

## Pass 2 follow-ups

- Pairing screen with QR code from desktop
- Live "synced" / "failed" badges on each pending item
- Finance + activity chart against a server snapshot cache
- Foreground service for "command sent" notifications
- Material 3 dark theme + brand
