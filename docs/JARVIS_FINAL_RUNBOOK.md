# Jarvis 2.0 — Operator Runbook

Single source of truth for running, verifying, and troubleshooting the live stack.
Last verified: 2026-06-06 against `jarvis-prod` (k3s, node `10.113.0.176`).
Honest status only — nothing here is claimed working unless it was actually tested.

## 0. Cluster status — READ FIRST (2026-07-04)

**The k3s cluster (`jarvis-prod`) is currently DOWN** after a host reboot on
2026-07-04. Every "10/10" / "8/8" / "READY" mark anywhere below reflects the last time
it was actually run (2026-06-06/07, while the cluster was up) — **none of it has been
re-run since this reboot.**

Recover with:
```bash
./scripts/product/jarvis-recover-after-reboot.sh
```
Then re-run `./scripts/jarvis-final-check.sh --repair` to re-verify before trusting any
status claim in this document again. See
[`docs/audit/2026-07-04-status-reconciliation.md`](audit/2026-07-04-status-reconciliation.md)
for the full picture (backlog reconciliation, the dual-k8s-tree tag-stomp fix, and the
three bonus modules).

> Conventions: gateway is `https://10.113.0.176` with header `Host: api.jarvis.local`.
> Login: `POST /api/v1/security/auth/login` (test user `test1111/test1111`).
> `kubectl` = `sudo k3s kubectl -n jarvis-prod`.

---

## 1. Start Jarvis
```bash
cd ~/Jarvis/Jarvis2.0
./jarvis up            # build+import images, deploy manifests, auto-fix host endpoint, status
# host model/voice daemons (systemd --user). NOTE the 14B brain unit is an INSTANCE
# unit named jarvis-llm@18080 (NOT "jarvis-llm" — that bare name does not exist):
systemctl --user status jarvis-llm@18080 jarvis-tts jarvis-pc-control 2>/dev/null
```
The local Qwen daemon on `:18080` must be up before chat works (see §4).

**Brain durability (verified 2026-06-06):** the 14B brain IS a managed systemd --user
service — `jarvis-llm@18080.service`, `UnitFileState=enabled`, `Restart=on-failure`,
and `loginctl Linger=yes` (so it starts on boot without an interactive login).
EnvironmentFile `~/.jarvis/llm-18080.env` holds the model/flags
(`-ngl 99 -fa on -ctk q8_0 -ctv q8_0`, ctx 8192, threads 6).
Restart test PASSED: `systemctl --user restart jarvis-llm@18080` → new PID → `:18080/health`
returns 200 within ~6s and the gateway chat returns `model=qwen3-14b`.
Manage it with: `systemctl --user {status,restart,stop,start} jarvis-llm@18080`.

## 2. Check health
```bash
./jarvis health         # one-line READY/DEGRADED verdict
./jarvis doctor         # GPU, disk, k3s, host LLM/TTS, ingress, host-model-daemon endpoint
./scripts/jarvis-smoke-verify.sh   # 8 end-to-end checks
```
Expected: `READY (deploys=22, all pods Running, LLM=200 TTS=200)`, smoke `8 passed, 0 failed`
_(last verified 2026-06-06 while the cluster was up; NOT re-run since the 2026-07-04 reboot — see §0)_.

## 3. Fix host-model-daemon endpoint (after the 14B brain goes silent)
The selectorless `host-model-daemon` Service can reset to the `192.0.2.1` placeholder
on a cluster re-apply, making the GPU brain unreachable.
```bash
./scripts/jarvis-host-endpoint-check.sh          # reports host + EndpointSlice + cluster reachability
./scripts/jarvis-host-endpoint-check.sh --fix    # re-patch Endpoints to node IP (idempotent)
```
`jarvis doctor` flags this loudly; `jarvis up` runs `--fix` automatically after deploy.

## 4. Verify the 14B brain
```bash
curl -s -m4 http://127.0.0.1:18080/health           # host daemon up?
TOKEN=$(curl -sk -H 'Host: api.jarvis.local' https://10.113.0.176/api/v1/security/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"test1111","password":"test1111"}' \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["accessToken"])')
curl -sk -H 'Host: api.jarvis.local' -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -X POST https://10.113.0.176/api/v1/llm/chat \
  -d '{"sessionId":"t","messages":[{"role":"user","content":"привет /no_think"}]}' | head
# expect "model":"qwen3-14b-q4_k_m.gguf"
```

## 5. Verify memory search
```bash
curl -sk -H 'Host: api.jarvis.local' -H "Authorization: Bearer $TOKEN" -H 'X-User-Id: 2' \
  -H 'Content-Type: application/json' -X POST \
  https://10.113.0.176/api/v1/memory/search -d '{"query":"jarvis","topK":2}'   # 200, semantic chunks
```

## 6. Verify Obsidian semantic search
```bash
curl -sk -H 'Host: api.jarvis.local' -H "Authorization: Bearer $TOKEN" -H 'X-User-Id: 2' \
  -H 'Content-Type: application/json' -X POST \
  https://10.113.0.176/api/v1/memory/search/unified -d '{"query":"парусное судно в океане","topK":5}'
# expect noteSearchMode=semantic and source=obsidian hits even with NO shared keywords.
```
`noteSearchMode` is `semantic` (pgvector via JdbcTemplate) or `keyword` (fallback if embeddings down).

## 7. Run Obsidian indexing safely
```bash
SECRET=$(grep '^SERVICE_JWT_SECRET=' ~/.jarvis/wake.env | cut -d= -f2-)
SERVICE_JWT_SECRET="$SECRET" python3 scripts/jarvis-obsidian.py index --limit 80
./jarvis obsidian status        # vault path + note count
```
Notes live in `~/JarvisVault`. Secret-shaped content is redacted before any write.

## 8. Avoid duplicate indexing
Re-indexing is now **idempotent (upsert)**: each note carries a stable
`source = "obsidian:<vault-path>"`; re-indexing the same file UPDATES the existing
row (and re-embeds if the content changed) instead of creating a duplicate.
Verify:
```bash
# index twice; row count for one file must not grow:
sudo k3s kubectl -n jarvis-prod exec postgres-pgvector-0 -- bash -lc \
  'psql -U "$POSTGRES_USER" -d jarvis_memory -tAc "select source,count(*) from memory_notes group by source having count(*)>1;"'
# (empty result = no duplicates per source)
```
**Historical duplicates** (created by reindexing BEFORE the upsert fix) can be cleaned
manually — soft-delete (reversible) all but the newest row per Obsidian source:
```sql
UPDATE memory_notes SET status='deleted', deleted_at=now()
 WHERE source LIKE 'obsidian:%' AND status <> 'deleted'
   AND memory_id NOT IN (
     SELECT DISTINCT ON (source) memory_id FROM memory_notes
      WHERE source LIKE 'obsidian:%' AND status <> 'deleted'
      ORDER BY source, created_at DESC);
```
Run it yourself (operator action — the agent guard blocks bulk prod-DB writes):
`sudo k3s kubectl -n jarvis-prod exec postgres-pgvector-0 -- psql -U <user> -d jarvis_memory -c "<sql>"`.
The vault files on disk are never touched; this only soft-deletes redundant DB rows.

## 9. Android E2E (manual — see also `scripts/jarvis-android-setup.sh`)
`sync-service` is `ClusterIP` by default; exposing it on prod is blocked by the agent
guard, so this is operator-run. The script is read-only and prints the exact commands.
```bash
./scripts/jarvis-android-setup.sh          # status + exact manual steps
./scripts/jarvis-android-setup.sh --dry-run  # server-side reachability smoke (no phone)
```
**Manual exposure:**
```bash
sudo k3s kubectl -n jarvis-prod patch svc sync-service -p \
  '{"spec":{"type":"NodePort","ports":[{"name":"http","port":8095,"targetPort":8095,"nodePort":30095,"protocol":"TCP"}]}}'
sudo k3s kubectl apply -f - <<'YAML'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: {name: sync-service-ingress-lan, namespace: jarvis-prod}
spec:
  podSelector: {matchLabels: {app: sync-service}}
  policyTypes: [Ingress]
  ingress: [{ports: [{protocol: TCP, port: 8095}]}]
YAML
curl -s -X POST http://10.113.0.176:30095/api/v1/sync/pairing/init -d '' ; echo   # expect JSON nonce
```
**Pairing checklist (phone on same LAN):**
1. Jarvis app → **Server** tab → enter `http://<host-LAN-ip>:30095`.
2. Tap **Спарить** → expect `Спарено ✓` (handshake signs `pairingNonceB64+kexPubB64`).
3. Verify registration: `kubectl get ... ` device list / sync-service logs show the device id.
4. **Health** tab → grant Health Connect (sleep, steps) → first sync within ~15 min.
5. **Finance** → add an expense → lands in life-tracker (occurredAt normalized to LocalDateTime).
**Troubleshooting:** see §15.

## 10. What is working (verified 2026-06-06; NOT re-run since the 2026-07-04 reboot — see §0)
- 14B GPU brain via host-model-daemon:18080; durable endpoint guard.
- Memory: semantic chunk search + **semantic Obsidian note search** (unified endpoint).
- Idempotent Obsidian indexing (upsert, no duplicates).
- Embeddings (note + chunk), Piper TTS, rule-based fast intents (incl. fixed volume up/down).
- sync-service finance/replay/pairing fixes; life-tracker @Transactional health-entry.
- smoke **8/8** _(last verified 2026-06-06 while cluster was up; not re-run since)_, Obsidian 11 unit tests, host-endpoint cluster-reachable.

## 11. What is partial / manual
- **Android E2E**: server side ready + verified server-side; full phone round-trip requires
  the manual NodePort + pairing in §9 (not verified without a device).
- **Desktop (JavaFX)**: launches on your display only — see §13. Not headless-verifiable here.
- **STT**: desktop-bound (voice-gateway + JavaFX client).

## 12. What NOT to enable
- **`JARVIS_HOST_DAEMON_ENABLED=true`** — only when llama.cpp coding (`:18081`) AND router
  (`:18082`) daemons are actually running. Otherwise llm-service fails readiness (probes them).
  Keep `LLM_SERVER_URL=http://host-model-daemon.jarvis-prod.svc.cluster.local:18080` + `=false`.
- coding/router model roles remain **DISABLED** until those daemons exist.

## 13. Desktop (JavaFX) — launch + sanity checklist
```bash
# requires a display (X/Wayland). Launcher app:
mvn -pl apps/desktop-javafx -am -DskipTests -Dspotless.check.skip=true clean install
mvn -pl apps/desktop-javafx javafx:run        # mainClass org.jarvis.launcher.LauncherApplicationKt
# headless server-side dry run (no display, token-based):
./scripts/e2e-desktop-dry-run.sh
```
Shell routes to sanity-check after launch: **Home, Planner, Life, Analytics, PC Control,
Smart Home, Vision Security/CV, Voice, Diagnostics, Settings, AI Runtime.**
Expected live status (top bar / AI Runtime / Diagnostics):
- 14B brain → reachable (AI Runtime shows model `qwen3-14b`).
- Memory → enabled (Memory/insights populate).
- Obsidian semantic search → returns notes by meaning.
- TTS → Voice tab plays audio.
- Android sync → pending/manual until §9 is done.

## 14. After a cluster re-apply
```bash
./scripts/jarvis-host-endpoint-check.sh --fix   # re-wire 14B brain endpoint
./jarvis health && ./scripts/jarvis-smoke-verify.sh
```

## 15. Android troubleshooting
| Symptom | Likely cause / fix |
|---|---|
| Phone can't reach host | Not same LAN, or host firewall. Use host LAN IP, `curl` the NodePort from another LAN device. |
| NodePort closed | sync-service still ClusterIP, or NetworkPolicy missing — re-run §9 patch + policy. |
| TLS/cert issue | App uses cleartext to the NodePort (payloads are E2E-encrypted); don't force HTTPS to `:30095`. |
| "Ed25519 KeyPairGenerator not available" | OEM JCA provider lacks Ed25519/X25519 — FIXED: `SyncCryptoKt` now uses the Bouncy Castle lightweight API (`bcprov-jdk18on`). Install the new `~/jarvis-app-debug.apk`. |
| Pairing signature failed | Client must sign `pairingNonceB64 + kexPubB64` (base64-string bytes) — fixed in Pairing.kt. Re-pair. |
| pairing → 401 | sync-service default Basic auth. Fix once: `bash scripts/fix-sync-auth.sh` (now stable). pairing/init should return a JSON nonce. |
| occurredAt validation (HTTP 400) | sync-service normalizes Instant→LocalDateTime; if still failing, check the phone sends ISO date-time. |
| No Health Connect permissions | Grant in Android Settings → Health Connect → Jarvis (sleep, steps). |

## 16. Voice E2E status (updated 2026-06-06 (g) — session intent now FIXED)

Verified working now:
- STT (Vosk) — host headless smoke + in-cluster self-test (`/api/v1/voice/diagnostics` = `STT_READY`).
- TTS (Piper) — `/api/v1/voice/synthesize` returns real WAV; host `:18090` 200.
- `/api/v1/voice/transcribe` no longer OOM-kills voice-gateway (memory limit 1Gi→2Gi).
- voice-gateway → nlp-service network path open (`voice-gateway-egress-nlp` + `nlp-ingress-from-voice-gateway`).
- **Voice session intent RESOLVES** (`POST /api/v1/voice/sessions/{id}/utterance`):
  `сделай тише`→volume_down, `сделай громче`→volume_up, `выключи звук`→mute (`resolved=true`, 0×403).
  Fixed by attaching `X-Service-Token` (SVC_INTERNAL, via existing `ServiceJwtProvider`) in BOTH
  `IntentResolver` (→ nlp) and `OrchestratorVoiceClient` (→ orchestrator). Shipped in
  **`voice-gateway:movie3`**. voice-gateway already has `SERVICE_JWT_SECRET` via `envFrom: jarvis-secrets`.

By-design (not a bug):
- Voice session ends `EXPIRED/TIMEOUT` for volume/mute because they are **risk=MEDIUM → require
  confirmation**. Headless REST has no confirmation channel, so the orchestrator safely rejects
  (host volume unchanged). Real execution needs a connected client to confirm ("Shall I, sir?").

Still requires operator hardware: real mic→speaker loop (mic + speakers); desktop JavaFX visual UX (display).

### Verification & recovery (updated 2026-06-06 (h))
- One-shot verifier: **`./scripts/jarvis-final-check.sh`** (read-only) — prints PASS/FAIL for jarvis
  health, doctor, host-model-daemon endpoint, llm chat (14B), voice diagnostics, voice session intent,
  desktop dry-run, obsidian tests, android dry-run. Last known: **10/10**
  _(2026-06-06, while cluster was up; NOT re-run since the 2026-07-04 reboot — see §0)_.
- The cluster-brain endpoint reset is recurring. **After any `kubectl apply`/re-apply of `k8s/base`,
  run one of:**
  ```
  ./scripts/jarvis-host-endpoint-check.sh --fix      # endpoint only
  ./scripts/jarvis-final-check.sh --repair           # endpoint fix + full re-verify
  ```
  `jarvis-smoke-verify.sh` now also fails loudly with that exact fix command if the placeholder returns.

| Symptom | Likely cause / fix |
|---|---|
| utterance → EXPIRED/TIMEOUT | risk=MEDIUM action awaiting confirmation; needs a connected client to confirm. Not an error. |
| smoke/llm chat fails, endpoint=192.0.2.1 | host-model-daemon endpoint reset. Fix: `./scripts/jarvis-host-endpoint-check.sh --fix` (or `jarvis-final-check.sh --repair`). Also ensure llm-service `LLM_SERVER_URL=http://host-model-daemon.jarvis-prod.svc.cluster.local:18080`, `HOST_DAEMON_ENABLED=false`. |
| /transcribe external 502/504 | Outermost nginx ingress proxy timeout on multipart; internal api-gateway path returns 200. |
| smoke: host-model-daemon + llm chat FAIL | Recurring host-model-daemon Endpoints reset to `192.0.2.1`. Fix: `scripts/jarvis-host-endpoint-check.sh --fix` + llm-service `LLM_SERVER_URL=http://host-model-daemon...:18080`. Host 14B `:18080` itself is healthy. |
| dry-run can't auth | Use `JARVIS_GATEWAY_URL=https://api.jarvis.local` (or IP) + `JARVIS_SMOKE_USER/PASS`; the script now logs in at `/api/v1/security/auth/login` and sends the `Host` header automatically. |
