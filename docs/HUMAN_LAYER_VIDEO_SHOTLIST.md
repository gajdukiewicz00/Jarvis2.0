# Jarvis — Human-Layer Video Shotlist

A literal, scene-by-scene script for a 5–8 minute demo video. Every command
here is lifted directly from
[HUMAN_LAYER_DEMO_RUNBOOK.md](HUMAN_LAYER_DEMO_RUNBOOK.md) — if a scene
fails on the day, look up the matching section number there for the fix.

This shotlist assumes **Track A** (local runtime, no cluster needed) as the
default recording path, with an optional Track B segment (§ Scene 9) if the
k3s cluster has been recovered beforehand. Record at 1920×1080, terminal font
size large enough to read on a compressed upload (≥16pt).

Total target runtime: **~7 minutes**.

| # | Scene | On-screen action | Exact command | Duration | Narration (voiceover / on-screen text) |
| - | ----- | ----------------- | -------------- | -------- | --------------------------------------- |
| 1 | Title card | Static title card over a terminal prompt | *(none)* | 0:00–0:15 (15s) | "Jarvis — a local-first AI assistant. Everything you're about to see runs on one machine, offline, no cloud calls." |
| 2 | Bring-up | Terminal: run the local runtime bring-up, let the startup log scroll | `ENABLE_LLM=true ENABLE_MEMORY=true ./scripts/runtime-up.sh` | 0:15–0:55 (40s) | "One command starts the whole backend: auth, orchestrator, voice, planner, the local LLM, and a vector memory store — as plain host processes, no Kubernetes required." |
| 3 | Readiness tally | Terminal: run the aggregator, let PASS/FAIL/SKIP lines print, freeze on the final tally line | `./scripts/jarvis-human-layer-check.sh` | 0:55–1:35 (40s) | "Before I demo anything, one script tells me honestly what's actually working right now." |
| 4 | Desktop launch + login | Screen recording: JavaFX window opens, click "Sign in", type `test1111` / `test1111` | `JARVIS_API_BASE_URL=http://localhost:8080 ./jarvis desktop` | 1:35–2:15 (40s) | "This is the desktop control center. Same backend, native JavaFX client." |
| 5 | Tab walkthrough | Click through Home → Brain/AI Chat → Memory → Planner → PC Control → Diagnostics | *(UI clicks only, no new command)* | 2:15–2:55 (40s) | "Chat with the local model, semantic memory, task planning, desktop control, live diagnostics — all in one shell." |
| 6 | Spoken voice loop — English | Terminal + mic: say "Hi Jarvis, what time is it?" when prompted, let the reply play | `JARVIS_API_BASE=http://localhost:8080 ./scripts/jarvis-voice-demo.sh --record 5` | 2:55–3:45 (50s) | "Full voice round trip: microphone → offline speech recognition → the local 14-billion-parameter model → text-to-speech — no internet used at any step." |
| 7 | Spoken voice loop — Russian | Terminal + mic: say a short Russian phrase, let the reply play | `JARVIS_API_BASE=http://localhost:8080 ./scripts/jarvis-voice-demo.sh --lang ru --record 5` | 3:45–4:25 (40s) | "It's bilingual — same pipeline, Russian speech recognition model instead." |
| 8 | Real desktop action | Terminal: run the host-bridge action, cut to the browser window that opens | `./scripts/jarvis-host-bridge.sh action --json '{"type":"OPEN_URL","target":"https://example.com","execute":true}'` | 4:25–5:05 (40s) | "And it can act on the real desktop — this is a safe, allow-listed action opening a URL, not a scripted mockup." |
| 9 | Memory recall | Desktop AI Runtime tab: type a note into "Persist note", click Save, then ask a related question in "Ask Jarvis" | *(UI clicks; backend is the already-running stack)* | 5:05–5:45 (40s) | "It remembers: I just told it a fact, and the model's answer pulls that fact back out of a vector database." |
| 10 | Track B (optional, cluster recovered) | Terminal: show `jarvis health` READY and `jarvis-final-check.sh` scrolling to a 9/9 pass | `./jarvis health && ./scripts/jarvis-final-check.sh` | 5:45–6:25 (40s) | "The same product also runs as a full Kubernetes deployment — here it is healthy end-to-end, with observability and Android sync layered on top." *(Skip this scene entirely if the cluster wasn't recovered before recording — cut straight to Scene 11.)* |
| 11 | Honest wrap-up | Title card listing what wasn't shown | *(none — reference [WHAT_TO_DEMO.md](WHAT_TO_DEMO.md) on screen)* | 6:25–7:00 (35s) | "What you didn't see today: the Android companion app needs a phone on the same network, and this is a single-user system by design. Everything else in this video is real, running on my own hardware." |

## Recording notes

- Do a full **dry run of every command in §2–§8** against the current
  machine state *before* recording — see
  [HUMAN_LAYER_DEMO_RUNBOOK.md](HUMAN_LAYER_DEMO_RUNBOOK.md) §3 for the
  one-shot pre-flight check.
- If the voice scenes (6–7) fail on the day for audio-hardware reasons, fall
  back to `--sample` (bundled clip, no microphone needed) and caption it as
  such rather than cutting the scene — silent failure looks worse than an
  honest caption.
- Scene 10 (Track B) is genuinely optional — the runbook and
  [WHAT_TO_DEMO.md](WHAT_TO_DEMO.md) are explicit that Track A alone is a
  complete, honest demo with no cluster dependency at all.
- Keep each terminal scene's font large and pause 1–2s after a command
  completes before cutting, so a paused viewer can read the final state.
