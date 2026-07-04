# What To Demo — Honest One-Pager

Full operator steps: [HUMAN_LAYER_DEMO_RUNBOOK.md](HUMAN_LAYER_DEMO_RUNBOOK.md).
Video script: [HUMAN_LAYER_VIDEO_SHOTLIST.md](HUMAN_LAYER_VIDEO_SHOTLIST.md).

## Demoable RIGHT NOW — Track A (local runtime, no cluster needed)

`ENABLE_LLM=true ENABLE_MEMORY=true ./scripts/runtime-up.sh` brings up the
entire backend as plain host processes. This works whether or not k3s is
running, because it doesn't touch Kubernetes at all.

What's real here, not mocked:

- Full service mesh (auth, orchestrator, NLP, planner, life-tracker,
  analytics, smart-home catalog, vision-security) with JWT auth end to end.
- Local 14B LLM chat (`/api/v1/llm/chat`) via the host Qwen daemon (`:18080`,
  systemd-managed, survives reboots) — no cloud API calls.
- Semantic memory: persist a note, ask a related question, get an answer
  that references it (pgvector + embedding worker + memory-service).
- Full spoken round trip, English and Russian: mic → Vosk STT (offline) →
  14B brain → Piper TTS (`:18090`, also systemd-managed) → speaker.
- JavaFX desktop shell: Control Center, Home, Brain/AI Chat, Voice, Memory,
  Finance, Planner, Life, Analytics, Smart Home, PC Control, Vision
  Security/CV, Diagnostics, Settings, AI Runtime.
- A genuine desktop action (not a mock): `scripts/jarvis-host-bridge.sh`
  opening a URL, or `pc-control` running non-stub (its default) executing
  real volume/notify/screenshot/hotkey actions.

## Demoable AFTER cluster recovery — Track B (k3s `jarvis-prod`)

**Currently DOWN on this machine.** Recovery is
`scripts/product/jarvis-recover-after-reboot.sh` (never `./jarvis up` for a
plain reboot recovery — see the runbook §2.1 for why). Once recovered and
verified with `./scripts/jarvis-final-check.sh`, everything Track A can do
also works against the cluster, plus:

- Grafana / Prometheus / Loki / Tempo observability dashboards.
- Obsidian semantic vault search (unified memory endpoint).
- Android companion app pairing — **manual only**, needs a phone on the
  same LAN and an operator-run NodePort exposure (blocked for automated
  agents by design; see `scripts/jarvis-android-setup.sh --dry-run` for the
  server-side-only proof that doesn't need a phone).

As of this writing the cluster hasn't been re-verified since the last
recorded check — treat Track B claims as "should work once recovered," not
"currently confirmed." Run `./scripts/jarvis-final-check.sh` after recovery
before relying on any Track B claim in a live demo.

## Honest caveats — hardware and environment

- **Voice needs a real microphone and speakers** on whichever machine runs
  the demo. Without one, `jarvis-voice-demo.sh --sample` (bundled clip) or
  `--wav <file>` still exercises the same STT→LLM→TTS pipeline, just not
  live from your voice.
- **The desktop app needs a graphical display** (X11/Wayland) — it cannot
  be demoed over a headless SSH session. The closest headless proxy is
  `./scripts/e2e-desktop-dry-run.sh`, which exercises the same safe
  read + orchestrator-intent path without ever opening a window.
- **`pc-control` real actions need your session's `DISPLAY`/`DBUS`** —
  if the runtime was started from a non-graphical shell, real desktop
  actions silently no-op until you run `scripts/jarvis-pc-control-up.sh`
  standalone (it exports those explicitly).

## What is NOT demoable at all right now

- **Android round-trip** — requires a physical phone on the same LAN plus
  a manual, operator-run NodePort exposure step; not something an automated
  agent or a single script can complete unattended.
- **Multi-user isolation** — Jarvis v1.0 is single-user by design.
- **Public-internet hardening** — this is a LAN/host-only system.
- **Real smart-home device control** — the device catalog is static, not
  wired to real hardware.

For the full working/not-working matrix beyond the demo surface, see
[CAPABILITIES.md](CAPABILITIES.md).
