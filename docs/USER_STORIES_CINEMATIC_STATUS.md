# Cinematic User Stories (US-001…US-110) — implementation status vs. code

Status grounded in the actual repo (controllers, services, schedulers, scripts) as of 2026-06-08.
**✅ DONE** = real, working code path · **🟡 PARTIAL** = core exists, gaps/hardware-gated ·
**⬜ TODO** = not built (or intentionally out of scope). Evidence = class / endpoint / script.

## Tally
- ✅ DONE: ~33 · 🟡 PARTIAL: ~49 · ⬜ TODO: ~28  → **~75% has real code behind it (DONE+PARTIAL)**.

## 1. Base voice assistant
| US | Status | Evidence / gap |
|---|---|---|
| 001 always-on voice | 🟡 | WakeWordDetector/Porcupine, Vosk STT, Piper TTS, proactive loop all exist; full mic→speaker loop needs hardware |
| 002 name + persona | ✅ | `jarvis-persona.txt`, PersonalizedPromptBuilder, user-profile preferences ("sir", dry wit) |
| 003 realtime dialog | 🟡 | SSE `/llm/chat/stream` + WS; voice session adds confirmation-wait latency |
| 004 control workstation | 🟡 | pc-control `/apps/open`,`/url/open`,`/window/focus`; "coding setup" window-layout macro not wired |
| 005 workshop context | 🟡 | vision `/ask-screen` + active-window read infer context; no explicit project binding |

## 2. Design / build (Iron Man 1)
| US | Status | Evidence / gap |
|---|---|---|
| 006 voice idea→plan | 🟡 | planner `/generate-document`,`/parse-task` (LLM); not full idea→architecture+files in product |
| 007 simulate before action | ✅ | pervasive `--dry-run` (android-setup, voice-smoke, demo-check read-only) |
| 008 risk warning | ✅ | `IntentRiskCatalog` (LOW/MEDIUM/HIGH) + spoken/text confirmation prompt |
| 009 auto tech calc | 🟡 | `GpuStatusService`, `/llm/runtime` (GPU/model/VRAM); no auto-tuning recommender |
| 010 optimal config (safe/perf) | ⬜ | model/JVM flags are manual; no profile recommender |
| 011 visual HUD | 🟡 | Control Center + Diagnostics dashboard + GPU status; no live desktop overlay widget |
| 012 voice UI control | 🟡 | pc-control window focus + open Grafana url; voice→window-switch intents partial |
| 013 build-error diagnosis | 🟡 | `jarvis doctor` explains; deep root-cause is the dev-harness layer |
| 014 post-action report | ✅ | `/status/report`, AuditPublisher, orchestrator reply envelopes |
| 015 hardware state | 🟡 | GpuStatus + `jarvis doctor` (disk/host); no thermal/auto-throttle |
| 016 confirm dangerous | ✅ | confirmation gate + PC allowlist (no arbitrary shell), hard safety rules |
| 017 voice UI theming | ⬜ | no dynamic theme switch by voice |
| 018 semantic file find | 🟡 | semantic search over Obsidian/memory (docs/notes), not codebase files |
| 019 voice dev-scenarios | 🟡 | `jarvis up` / CLI + pc-control; one-command "dev mode" partial |
| 020 auto checklist | 🟡 | planner daily/tasks; PRD/plan generation is harness-side |

## 3. Telemetry / diagnostics
| US | Status | Evidence / gap |
|---|---|---|
| 021 live telemetry | ✅ | `/status/report`, `jarvis health/doctor`, `/llm/runtime` GPU |
| 022 auto failure detection | ✅ | readiness probes, SubsystemHealth, `jarvis doctor`, proactive |
| 023 safe mode on overload | ⬜ | no load-based auto-throttle |
| 024 test-flight sandbox | 🟡 | demo/dry-run modes; not isolated env per feature |
| 025 explain limitations | ✅ | honest 401/403, "не могу" replies, dry-run skips, LocalOnlyEnforcer |
| 026 emergency recovery | ✅ | `final-check --repair`, `host-endpoint-check --fix`, `rollout undo`, doctor |
| 027 session activity log | ✅ | AuditPublisher, `/api/v1/audit`, ConfirmationAuditor |

## 4. Focus / incident
| US | Status | Evidence / gap |
|---|---|---|
| 028 focus mode | 🟡 | planner `/focus`,`/focus-mode`,`/pomodoro`; app-muting not wired as one cmd |
| 029 incident mode | 🟡 | `jarvis doctor/logs` + `/status/report`; one-command bundle is harness-side |
| 030 threat prioritization | ✅ | IntentRiskCatalog severity, vision IncidentRecord severity, MonitoringDecisionEngine |
| 031 route to goal | 🟡 | planner; diagnose→fix→test→commit routing is harness-side |
| 032 don't-shoot-foot block | ✅ | confirmation gate + allowlist + hard rules |

## 5. Health (Iron Man 2)
| US | Status | Evidence / gap |
|---|---|---|
| 033 user-state monitoring | 🟡 | Health Connect (sleep+steps) + wellness; workouts/wearables broader = TODO |
| 034 wellbeing warning | 🟡 | ProactiveWarningEngine (LOW_SLEEP) + analytics sleep↔overtime correlation |
| 035 private health dashboard | 🟡 | life-tracker wellness + privacy mode + desktop LIFE screen; local-only |
| 036 dynamic plan adjust | ⬜ | no energy-based replanning intent |
| 037 bad-pattern detection | 🟡 | analytics insights/weekly + proactive patterns |

## 6. Research assistant (Iron Man 2)
| US | Status | Evidence / gap |
|---|---|---|
| 038 research w/ sources | ⬜ | LocalOnlyEnforcer = no web research (by design) |
| 039 analyze old notes | ✅ | Obsidian semantic search + pgvector RAG recall + `/unified` |
| 040 visual idea→task | 🟡 | vision OCR/ask-screen (screen); photo→architecture VLM off-by-default |
| 041 generate prototype | ⬜ | harness-side capability, not product |
| 042 compare options A/B/C | ⬜ | harness-side capability, not product |
| 043 lab mode (isolated) | ⬜ | not built |
| 044 experiment versioning | ⬜ | not built |

## 7. Access / secrets security
| US | Status | Evidence / gap |
|---|---|---|
| 045 access-control audit | 🟡 | ServiceJwt, security-service auth; no token/port inventory report |
| 046 suspicious process detect | 🟡 | vision monitoring (camera/screen); no process/network IDS |
| 047 read-only risky integrations | ✅ | safe reads by design; writes need confirmation |
| 048 secret protection | ✅ | LogSanitizer + secret-redaction filter (logs + Obsidian) |
| 049 emergency revoke checklist | 🟡 | security-service exists; no revoke-checklist generator |

## 8. Smart home / infra (Avengers)
| US | Status | Evidence / gap |
|---|---|---|
| 050 smart home dashboard | ✅ | smart-home catalog/devices/action + desktop SMART_HOME + MQTT |
| 051 energy consumption | ⬜ | not built |
| 052 home scenes | ✅ | `/scenes/{name}/activate` (movie_night verified) |
| 053 geo-based scenes | ⬜ | no location/geofence |
| 054 calendar integration | ✅ | life-tracker `/calendar/events` + daily plan uses calendar |
| 055 team/share mode | ⬜ | cloud-relay exists; safe-summary sharing not built |
| 056 infra status | ✅ | `/status/report`, `jarvis health`, 26-service status |
| 057 impossible detection | ✅ | honest cause + LocalOnlyEnforcer explains |

## 9. Remote activation
| US | Status | Evidence / gap |
|---|---|---|
| 058 remote workspace activation | ⬜ | no Telegram/Slack remote trigger in product |
| 059 device handoff | 🟡 | Android E2E sync (phone→server); not bidirectional task handoff |
| 060 emergency tools launcher | 🟡 | `jarvis` CLI/doctor; not a one-command toolset |
| 061 remote command permission gate | ✅ | confirmation gate + service-token + allowlist |

## 10. Risk / autonomy (Ultron lesson)
| US | Status | Evidence / gap |
|---|---|---|
| 062 risk engine | ✅ | IntentRiskCatalog |
| 063 human approval gate | ✅ | confirmation flow (verified end-to-end: APPROVED→queue) |
| 064 autonomy audit log | ✅ | AuditPublisher, ConfirmationAuditor |
| 065 autonomy modes | 🟡 | ConfirmationStrategy: AutoApprove/AutoDeny/CliPrompt + DemoMode; no clean 4-level selector |
| 066 kill switch | ✅ | KillSwitchManager + per-agent `/agent/{id}/kill-switch` |

## 11. Modularity / agents (Iron Man 3)
| US | Status | Evidence / gap |
|---|---|---|
| 067 modular architecture | ✅ | 20+ independent microservices |
| 068 plugin system | ⬜ | no plugin registry |
| 069 agent swarm | 🟡 | desktop agent executor + command pipeline; no multi-role research swarm in product |
| 070 agent coordination | ⬜ | not in product (harness-side) |
| 071 agent isolation | 🟡 | k8s namespace + NetworkPolicy isolate services; no per-agent worktrees in product |
| 072 command queue | ✅ | PendingCommandRegistry + RabbitMQ QUEUE_AGENT_EXECUTE (pending/running/done/failed) |
| 073 offline fallback | ✅ | fully local (LocalOnlyEnforcer); timers/notes/pc-control/memory work offline |
| 074 low-resource mode | ⬜ | not built |

## 12. Home attack / recovery (Iron Man 3)
| US | Status | Evidence / gap |
|---|---|---|
| 075 home security alert | 🟡 | vision-security camera/incidents/EmailAlertService; network alerts = TODO |
| 076 emergency profile | 🟡 | privacy mode + kill switch; not bundled as one profile |
| 077 auto backup before risk | 🟡 | `jarvis backup/restore`; not auto-before-migration |
| 078 env recovery after crash | ✅ | `final-check --repair`, `host-endpoint --fix`, `rollout undo`, doctor |
| 079 context after restart | 🟡 | memory persists; "resume last task" not wired |
| 080 protect contacts | ⬜ | not built |

## 13. OSINT (Iron Man 3)
| US | Status | Evidence / gap |
|---|---|---|
| 081 OSINT research | ⬜ | LocalOnly = no web (by design) |
| 082 image/video analysis | 🟡 | OCR/ask-screen on screenshots; video = TODO |
| 083 link/graph relations | ⬜ | not built |
| 084 hypothesis testing | ⬜ | not built |

## 14. House Party Protocol (Iron Man 3)
| US | Status | Evidence / gap |
|---|---|---|
| 085 mass agent launch | 🟡 | command queue; multi-agent fan-out is harness-side |
| 086 agent roles | ⬜ | not in product |
| 087 agent observation | 🟡 | AgentLiveFeed + per-agent status (`/api/v1/agent`) |
| 088 stop specific agent | ✅ | `/api/v1/agent/{agentId}/kill-switch` |
| 089 final agent report | 🟡 | per-action reports; unified swarm report harness-side |
| 090 clean slate | 🟡 | obsidian dedup done; temp/branch cleanup is harness-side |

## 15. Ultron-safety (Age of Ultron)
| US | Status | Evidence / gap |
|---|---|---|
| 091 sandbox new AI features | 🟡 | NetworkPolicy default-deny + service isolation + no secret access |
| 092 permission model | 🟡 | ServiceJwt roles (SVC_INTERNAL) + PC allowlist + LocalOnly; not fine-grained per-integration |
| 093 memory integrity | 🟡 | notes carry source/created_at + delete + forget; confidence partial |
| 094 prompt-injection protection | 🟡 | LogSanitizer + strict JSON persona contract; explicit external-as-data guard weak |
| 095 system vs user memory | 🟡 | memory categories/scopes + chunk-vs-note; not 4 clean tiers |
| 096 core backup | 🟡 | `jarvis backup/restore` |
| 097 migrate context (encrypted) | 🟡 | Android E2E sync + memory `/notes/export`; full encrypted profile export partial |
| 098 embodied (multi-surface) | 🟡 | desktop + phone + host voice; watch/browser/overlay = TODO |
| 099 moral limits | ✅ | persona candid, advises not coerces; confirmation for actions |
| 100 red team before autonomy | 🟡 | smoke/final-check/demo-check gates; deep adversarial tests harness-side |

## 16. Vision-line (becomes more)
| US | Status | Evidence / gap |
|---|---|---|
| 101 stable personality | ✅ | `jarvis-persona.txt` persisted across sessions |
| 102 months-long context | ✅ | pgvector long-term memory + semantic recall |
| 103 self-explanation | 🟡 | DialogResponse; reasoning summary not always surfaced |
| 104 confidence self-assessment | 🟡 | intent confidence exists; answer-level confidence = TODO |
| 105 personality growth | 🟡 | user-profile preferences; "так больше не отвечай" not wired |

## 17. Other film mechanics
| US | Status | Evidence / gap |
|---|---|---|
| 106 FRIDAY tactical options | ⬜ | no "3 options" generator in product |
| 107 EDITH mobile interface | 🟡 | Android app (tasks/health/finance/settings); no PC-status/quick-commands |
| 108 Karen onboarding | ⬜ | no "you can say…" suggestions |
| 109 auto-suggest on stuck | 🟡 | proactive loop is screen-based, not test-failure-triggered |
| 110 training mode | ⬜ | not built |

## Implemented in autonomous pass (2026-06-08)
- **US-108 onboarding / US-106 tactical options → ✅ DONE**: new `GET /api/v1/voice/help`
  (categorised "ты можешь сказать…" built from real intents + tactical hints) in `voice-gateway:movie5`,
  plus a "Ты можешь сказать" panel on the Control Center dashboard (desktop 324 tests pass).
- **US-039 / US-101 / US-102 strengthened**: `scripts/jarvis-ask.sh` + `jarvis ask "…"` — talk to the
  14B brain (with persona + server-side RAG) from the terminal; `--speak` plays the Piper answer.
- Stack stayed green throughout (smoke 8/8, final-check 10/10, 26/26 pods, voice-gateway restart 0).

## Strongest cinematic pillars already real
Risk engine + human approval gate + kill switch + audit (US-008/016/030/032/062/063/064/066),
local brain + long-term memory + RAG (US-002/039/102), telemetry/recovery (US-021/022/026/078),
smart-home scenes + dashboard (US-050/052/056), modular microservices + command queue (US-067/072/073),
honest limits + moral limits (US-025/057/099).

## Biggest cinematic gaps (highest demo value to build next)
1. Autonomy mode selector (manual/suggest/semi/auto) — US-065 (strategies already exist).
2. Answer-level confidence + reasoning summary — US-103/104 (LLM prompt + DialogResponse field).
3. "You can say…" onboarding / capability suggester — US-108.
4. Backend executor so confirmed PC actions run headless — closes US-004/012/063 end-to-end.
5. Energy/thermal telemetry + low-resource mode — US-015/023/051/074.
