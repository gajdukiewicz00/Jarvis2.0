# Jarvis — Human-Layer Demo Runbook

This is the operator runbook for showing Jarvis to a human: a recruiter, a
thesis examiner, a friend. It merges the two independent ways the stack can be
demoed:

- **Track A — local runtime.** `scripts/runtime-up.sh` starts every Java
  service as a plain host process (no Kubernetes). This works even when the
  k3s cluster is down, and is the fastest path to a live demo.
- **Track B — k3s `jarvis-prod`.** The full cluster deployment described in
  [JARVIS_FINAL_RUNBOOK.md](JARVIS_FINAL_RUNBOOK.md). **Cluster status: HEALTHY**
  (verified 2026-07-05 — 28/28 pods Running, gateway up, brain answering, `./jarvis
  doctor` all-green). §2.1 below still applies if you land here after a reboot or a
  DHCP lease change and something looks off.

Both tracks share the same host-resident "brains": the Qwen 14B LLM daemon on
`:18080` and Piper TTS on `:18090` are systemd `--user` services
(`jarvis-llm@18080.service`, `jarvis-tts.service`) with `Restart=on-failure`
and `loginctl Linger=yes` — they survive reboots on their own and don't need
to be started by either track. Confirm they're up before any voice demo:

```bash
systemctl --user status jarvis-llm@18080 jarvis-tts jarvis-pc-control 2>/dev/null
```

Both tracks also share the same test account: **`test1111` / `test1111`**
(the credentials every smoke/demo script in `scripts/` defaults to).

---

## 0. Which track should I run?

| Question | Answer |
| --- | --- |
| Is the k3s cluster reachable right now? | If unsure, run `./scripts/jarvis-human-layer-check.sh` (§5) — it tells you. |
| Cluster is down / you just want the fastest demo | **Track A** (§1). No cluster dependency at all. |
| You specifically need to show Kubernetes, Grafana, or Android pairing | **Track B** (§2). Requires cluster recovery first. |
| You need voice or the desktop UI | Either track works, but you need a **microphone + speakers** for voice and a **graphical display** (X11/Wayland) for the desktop app — see [WHAT_TO_DEMO.md](WHAT_TO_DEMO.md) for the honest caveats. |

---

## 1. Track A — local runtime (works with no cluster)

### 1.1 Bring-up

```bash
cd ~/Jarvis/Jarvis2.0
ENABLE_LLM=true ENABLE_MEMORY=true ./scripts/runtime-up.sh
```

`runtime-up.sh` starts, in order: `security-service`, `user-profile`,
`nlp-service`, `orchestrator`, `voice-gateway` (`:8081`), `pc-control`
(`:8084`), `vision-security-service`, `smart-home-service`, `life-tracker`,
`analytics-service`, `api-gateway` (`:8080`), `planner-service`, and — because
`ENABLE_LLM`/`ENABLE_MEMORY` are set — `llm-server`, `embedding-service`,
`memory-service`, `llm-service`. It also brings up a managed Postgres
container (`podman`) if no external DB is configured.

Run this from a terminal inside your graphical session (not a headless SSH
session) — see §1.4 for why that matters for PC actions.

Verify:

```bash
./scripts/runtime-status.sh    # every line should read health=ready / reachable
```

If any service stays down: `tail -200 ~/.jarvis/logs/local-runtime/<service>.log`.

Stop when done: `./scripts/runtime-down.sh` (keeps the local Postgres
container alive by default; pass `--purge` to also remove it).

### 1.2 Spoken voice loop — EN + RU

```bash
JARVIS_API_BASE=http://localhost:8080 ./scripts/jarvis-voice-demo.sh --record 5
JARVIS_API_BASE=http://localhost:8080 ./scripts/jarvis-voice-demo.sh --lang ru --record 5
```

Flow: mic (`arecord`/`pw-record`) → Vosk STT (offline) → `/api/v1/llm/chat`
on the 14B brain → Piper TTS → speaker. The transcript and reply print to the
terminal; artifacts (`transcript.txt`, `response.txt`, `answer.wav`) are saved
under `/tmp/jarvis-voice-demo/` for later inspection.

No microphone on the demo machine? Use the bundled sample clip instead —
`./scripts/jarvis-voice-demo.sh --sample` — or point `--wav <file>` at any
16-bit WAV.

### 1.3 Desktop walkthrough

```bash
JARVIS_API_BASE_URL=http://localhost:8080 ./jarvis desktop
```

This always compiles and runs the JavaFX shell fresh (`javafx:run`), so you
never see a stale build. Needs a display — the script warns if one isn't
present.

Login: **`test1111` / `test1111`** (printed by the launcher itself).

Walk the shell routes (from `ShellRoute.kt`): Control Center, Home,
Brain / AI Chat, Voice — commands help, Voice, Memory, Finance, Planner,
Life, Analytics, Analytics Insights, Smart Home, PC Control,
Vision Security / CV, Proactive, Security / Privacy, Sync / Pairing,
Diagnostics, AI Runtime, Settings. The **AI Runtime** tab only appears when
`ENABLE_LLM` or `ENABLE_MEMORY` was set before bring-up.

A good memory-recall beat: in **AI Runtime**, persist a short note, then ask
a related question in a separate turn — the reply should reference the note
(proves embedding worker → pgvector → memory-service → llm-service context
injection all work together).

No display available? Use the headless server-side proxy instead:

```bash
./scripts/e2e-desktop-dry-run.sh
```

It reads the active window (a SAFE `pc-control` capability) and submits a
benign "open browser" intent to the orchestrator — no window ever opens, but
it proves the same request path the desktop app would exercise.

### 1.4 Real PC action

Two independent paths — either is a legitimate "look, it really touches the
desktop" beat:

**(a) Host bridge — always real, no service dependency.**

```bash
./scripts/jarvis-host-bridge.sh health   # confirms it sees your display/LLM/STT/TTS
./scripts/jarvis-host-bridge.sh action --json '{"type":"OPEN_URL","target":"https://example.com","execute":true}'
```

`OPEN_URL` is on the bridge's `SAFE` allow-list (`classify_action`), so with
`execute:true` it runs immediately via `xdg-open` — no confirmation step
needed. This is the one place host-bound operations (display/mic/speakers)
run; it never shells out arbitrarily, only through the allow-list.

**(b) `pc-control` HTTP, non-stub.** `apps/pc-control/src/main/resources/application.yml`
defaults `pc-control.stub-mode` to `${PC_CONTROL_STUB_MODE:false}` — so the
`pc-control` instance `runtime-up.sh` already started in §1.1 is executing
*real* desktop actions by default, provided it inherited your graphical
`DISPLAY`/`DBUS_SESSION_BUS_ADDRESS` (i.e. you ran `runtime-up.sh` from a
terminal on your desktop, not a headless shell). If it didn't, run
`pc-control` standalone instead, which exports those explicitly:

```bash
systemd-run --user --unit=jarvis-pc-control scripts/jarvis-pc-control-up.sh
curl -s http://localhost:8084/actuator/health/readiness
```

Requires `SERVICE_JWT_SECRET` in `~/.jarvis/wake.env`.

---

## 2. Track B — k3s `jarvis-prod` (currently HEALTHY — verified 2026-07-05)

### 2.1 Recover (only needed if the cluster is actually down)

```bash
systemctl is-active k3s || sudo systemctl restart k3s
./scripts/product/jarvis-recover-after-reboot.sh --dry-run   # see what's stale first
./scripts/product/jarvis-recover-after-reboot.sh              # then actually recover
./scripts/jarvis-host-endpoint-check.sh --fix                 # re-wire the 14B brain endpoint
./jarvis health && ./scripts/jarvis-smoke-verify.sh
```

**Do not run `./jarvis up` just to recover from a reboot.** `jarvis up`
re-applies manifests via kustomize, and — until the documented `REPRO-1` fix
lands — that resets every image tag back to `:local`, wiping the
movie-tagged feature images (voice intent auth, confirmation fix, bank
parser, semantic memory, etc. — see the comment header in
`jarvis-recover-after-reboot.sh`). `jarvis-recover-after-reboot.sh` instead
only `kubectl delete pod`s the stale pod objects; their existing
Deployment/StatefulSet controllers recreate them from the unchanged spec
already sitting in etcd — no manifests touched, no tags stomped. It brings
datastores/brokers back first, then app pods, then polls until everything is
`Running`/`Ready` (or reports what's still stuck).

### 2.2 Verify

```bash
./jarvis status
./jarvis doctor
./scripts/jarvis-final-check.sh          # 9-point PASS/FAIL, see JARVIS_FINAL_RUNBOOK.md §16
```

Re-run `./scripts/jarvis-host-endpoint-check.sh --fix` after any
`kubectl apply` — the selectorless `host-model-daemon` Service resets to the
`192.0.2.1` placeholder on re-apply, which silently makes the 14B brain
unreachable from in-cluster services.

### 2.3 Voice loop / desktop / PC action against the cluster

Same commands as §1.2–§1.4, pointed at the ingress instead of localhost. The node IP
is DHCP-assigned and changes on reboot — resolve it dynamically rather than
hardcoding it (see [JARVIS_FINAL_RUNBOOK.md](JARVIS_FINAL_RUNBOOK.md) §0):

```bash
NODE_IP=$(sudo k3s kubectl -n jarvis-prod get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')
JARVIS_API_BASE=https://$NODE_IP JARVIS_API_HOST=api.jarvis.local ./scripts/jarvis-voice-demo.sh --record 5
./jarvis desktop        # defaults to https://api.jarvis.local already
```

Voice session **risk=MEDIUM** actions (volume up/down, mute) require a
connected desktop client to confirm — a headless REST call correctly times
out `EXPIRED`/`TIMEOUT` waiting on that confirmation gate. That is by design,
not a bug (see [JARVIS_FINAL_RUNBOOK.md](JARVIS_FINAL_RUNBOOK.md) §16).
`OPEN_URL`-style SAFE actions via the host bridge (§1.4a) don't hit this gate.

Grafana / observability is Track-B-only (no equivalent in Track A):

```bash
./scripts/verify-observability.sh
# https://grafana.jarvis.local — admin password in ~/.jarvis/secrets/secrets.env
```

Android pairing is manual/operator-only in both tracks — see
[JARVIS_FINAL_RUNBOOK.md](JARVIS_FINAL_RUNBOOK.md) §9 and
`scripts/jarvis-android-setup.sh --dry-run` for the server-side-only proof.

---

## 3. One-shot readiness tally

Before recording anything, run:

```bash
./scripts/jarvis-human-layer-check.sh
```

It reports (read-only, non-destructive): the active runtime status snapshot
(Track A `runtime-status.sh` or Track B `jarvis health`, whichever applies),
the voice toolchain (`jarvis-voice-daemon-check.sh`), a safe desktop
dry-run (`e2e-desktop-dry-run.sh`), and — only if the k3s cluster actually
answers — the full Track B `jarvis-final-check.sh`. It prints one
PASS/FAIL/SKIP tally at the end so you know exactly what's demo-ready right
now without touching anything.

---

## 4. Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| `runtime-up.sh` exits with "podman: command not found" | podman missing | `sudo apt install podman` |
| Voice demo: "no mic capture tool" | no `arecord`/`pw-record` | use `--sample` or `--wav <file>` |
| Voice demo: "brain returned no reply" | host LLM daemon down, or (Track B) endpoint placeholder | `systemctl --user restart jarvis-llm@18080`; on Track B also `./scripts/jarvis-host-endpoint-check.sh --fix` |
| Voice demo: "TTS failed" | Piper daemon down | `systemctl --user status jarvis-tts`; `curl :18090/health` |
| Desktop app doesn't appear | no graphical display on this host | use `./scripts/e2e-desktop-dry-run.sh` instead |
| `pc-control` action has no visible effect | it started stub-mode or lost `DISPLAY` | run `scripts/jarvis-pc-control-up.sh` standalone (§1.4b) |
| Track B: llm chat / smoke fails with `host-model-daemon` at `192.0.2.1` | endpoint reset by a cluster re-apply | `./scripts/jarvis-host-endpoint-check.sh --fix` (or `jarvis-final-check.sh --repair`) |
| Track B: pods stuck `ContainerStatusUnknown`/`Error`/`CrashLoopBackOff` after reboot | kubelet lost track of pre-reboot containers | `./scripts/product/jarvis-recover-after-reboot.sh` (§2.1) — never `./jarvis up` |
| Voice session (volume/mute) ends `EXPIRED`/`TIMEOUT` | risk=MEDIUM confirmation gate, no connected client | expected; demo `OPEN_URL` via the host bridge instead (§1.4a) |

For deeper per-service troubleshooting see [docs/services/](services/); for
the full k3s operator playbook see
[JARVIS_FINAL_RUNBOOK.md](JARVIS_FINAL_RUNBOOK.md); for the original
5-minute walkthrough this runbook extends see [DEMO.md](DEMO.md).
