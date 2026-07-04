# START HERE — J.A.R.V.I.S. (owner guide)

Your local, on-device cinematic assistant. Qwen3-14B brain on your RTX 5070, running in a
k3s cluster, with voice, memory, and safe PC control. This page is the 60-second path to a
working demo. Run everything from the repo root: `~/Jarvis/Jarvis2.0`.

---

## 1. Run this first (heals + verifies everything)
```bash
./scripts/jarvis-final-check.sh --repair
```
Expect **10/10 PASS**. `--repair` fixes the one recurring gremlin (the host-model-daemon
endpoint reset) automatically. If you only want to check without changing anything, drop `--repair`.

## 2. Is it demo-ready?
```bash
./scripts/jarvis-demo-check.sh
```
Prints **READY FOR DEMO** (or the exact failing step + its fix).

## 3. Start the desktop Control Center (needs a display)
```bash
mvn -pl apps/desktop-javafx javafx:run
```
Opens on the **Control Center** dashboard: live status cards (Brain, RAG, Obsidian, Voice,
STT, TTS, PC Control, Proactive), "What Works Now", "Needs Human", and the voice-demo checklist.

## 4. Test real voice (needs microphone + speakers)
Full spoken loop — record → transcribe → 14B brain → Piper voice → play:
```bash
./scripts/jarvis-voice-demo.sh --record 5
```
Or the smoke variant that saves a wav:
```bash
./scripts/jarvis-voice-smoke.sh --record 5 --tts-out /tmp/jarvis-demo.wav && aplay /tmp/jarvis-demo.wav
```
No mic handy? Use the bundled clip (no microphone needed):
```bash
./scripts/jarvis-voice-demo.sh --sample
```

## 4a. Talk to Jarvis from the terminal (brain + memory)
```bash
./jarvis ask "что ты помнишь про проект Jarvis?"
./jarvis ask --speak "ответь одной фразой: ты онлайн?"   # speaks the answer (needs speakers)
```
Ask what it can do: `curl -sk -H 'Host: api.jarvis.local' -H "Authorization: Bearer <token>" https://10.113.0.176/api/v1/voice/help`

## 4c. Parse a bank push notification → transaction (local-only AI-light)
```bash
./jarvis parse-bank "Płatność kartą 23,99 PLN Lidl"
./jarvis parse-bank --store "Payment 12.99 USD Amazon"   # save if HIGH confidence
```
Deterministic + on-device: extracts amount/currency/merchant/type, masks the card (`**** 1234`),
categorises (Lidl→groceries, Netflix→subscriptions, Uber→transport), scores confidence; low-confidence
drafts go to a manual inbox, never straight into finances.

## 4b. See the proactive awareness (it watches your screen and reasons)
```bash
./scripts/jarvis-proactive-demo.sh            # dry-run: observe + decide, NO audio
./scripts/jarvis-proactive-demo.sh --speak-once   # speak ONE line (needs speakers)
```
Disable the always-on loop: `systemctl --user disable --now jarvis-proactive.service`

## 5. Controlled confirmation demo (changes volume, then restores it)
```bash
./scripts/jarvis-demo-check.sh --approve-volume-demo
```
Proves the safety flow: voice → intent (`сделай тише` → volume_down) → **confirmation required**
→ APPROVED → command executes → volume restored to original. Safe: only volume, always restored.
> The actual host volume change requires the **desktop app running** (it is the executor). Headless,
> the command is approved and queued; with the desktop app open, the loop completes audibly.

---

## What works now (verified)
- **14B brain chat** (`qwen3-14b-q4_k_m.gguf`), 100% local on GPU.
- **RAG memory** recall, **Obsidian semantic search**, idempotent upsert (duplicates cleaned).
- **Voice intent**: `сделай тише`→volume_down, `сделай громче`→volume_up, `выключи звук`→mute.
- **STT** (Vosk, EN+RU) and **TTS** (Piper neural) engines.
- **PC-control safe reads** (volume, windows, system info).
- **Confirmation gate** (risk=MEDIUM actions require explicit approval).
- smoke-verify **8/8**, final-check **10/10**, demo-check **READY**.

## What still needs your hardware
- **Microphone + speakers** — the live spoken loop (steps 4–5 audibly).
- **A display** — the desktop GUI and executing confirmed PC actions (the desktop app is the executor).
- **An Android phone** — pairing / mobile E2E (`scripts/jarvis-android-setup.sh --dry-run` proves the server side).

## Do NOT enable (will break or unsafe)
- `HOST_DAEMON_ENABLED=true` — the coding/router daemons on **18081/18082** are not running; leave it `false`.
- Don't touch ports **18081 / 18082**.

## Android phone (optional bonus — already half-wired)
The LAN exposure is **already done**: `sync-service` is on **NodePort 30095** with a LAN-ingress
NetworkPolicy. One deliberate security step remains (it must be an explicit operator action because
it opens an unauthenticated endpoint to the LAN — by design, see below). Run **once**:
```bash
sudo k3s kubectl -n jarvis-prod set env deploy/sync-service \
  SPRING_AUTOCONFIGURE_EXCLUDE='org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration'
sleep 30 && curl -s -X POST http://10.113.0.176:30095/api/v1/sync/pairing/init -d '' ; echo   # expect JSON nonce
```
**Why this is safe by design:** sync-service is the device-facing, local-first sync endpoint. The
phone pairs over an end-to-end encrypted handshake (Ed25519 signature + X25519 key exchange) and every
payload is ChaCha20-Poly1305 sealed with the per-device session key. The HTTP channel is intentionally
unauthenticated — confidentiality + integrity live in the E2E envelope, not the transport. (A baked-in
version of this decision is written in `apps/sync-service/.../config/SyncSecurityConfig.java`.)

Then on the phone: install `~/jarvis-app-debug.apk`, open the **Server** tab, enter
`http://10.113.0.176:30095` (or this machine's LAN IP), tap **Спарить**, grant Health Connect.
> Full pairing requires a physical phone on the same LAN, so it is validated by hand, not headless.

## Emergency fixes
```bash
./scripts/jarvis-host-endpoint-check.sh --fix   # brain unreachable / llm chat fails (endpoint reset)
./scripts/jarvis-final-check.sh --repair        # one-shot heal + full re-verify
```
Common symptoms → fixes live in `docs/JARVIS_FINAL_RUNBOOK.md` §16.

---

## The 90-second live demo script
1. `./scripts/jarvis-demo-check.sh` → show **READY FOR DEMO**.
2. `mvn -pl apps/desktop-javafx javafx:run` → show the **Control Center** dashboard.
3. `./scripts/jarvis-voice-demo.sh --record 5` → speak "what's on my screen?", hear Jarvis answer.
4. `./scripts/jarvis-demo-check.sh --approve-volume-demo` → show the safe confirmation flow.
5. Done — everything is local, on your GPU, no cloud.
