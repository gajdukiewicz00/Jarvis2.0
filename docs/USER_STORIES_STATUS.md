# User Stories — implementation status (701 backlog)

Classification of `~/Downloads/jarvis_user_stories_backlog_701.md` against the actual
codebase + live k3s `jarvis-prod` (2026-06-05). Status: **DONE** (works) · **PARTIAL**
(core present, gaps) · **TODO** (not built). Slight count drift (~+8) from overlapping
section ranges; treat as directional.

## Overall

| Status | Count | Share |
|---|---:|---:|
| ✅ DONE | ~289 | ~41% |
| 🟡 PARTIAL | ~137 | ~20% |
| ⬜ TODO | ~283 | ~40% |

**~60% of the backlog is already real (DONE + PARTIAL).** The backend, brain, voice,
PC-control, security and infra are strong; the TODO tail is mostly UX screens, the
analytics "insight" layer, memory CRUD/UI, extra life-trackers, smart-home expansion,
and nice-to-have voice features.

## Per-epic

| Epic | Total | ✅ DONE | 🟡 PARTIAL | ⬜ TODO |
|---|---:|---:|---:|---:|
| Architecture / Microservices | 21 | 19 | 1 | 1 |
| Observability | 5 | 5 | 0 | 0 |
| Infrastructure / K8s / Observability | 50 | 28 | 9 | 13 |
| LLM / Agent Brain | 55 | 27 | 14 | 14 |
| PC Control / Desktop Automation | 66 | 39 | 8 | 19 |
| Security / Privacy / Permissions | 47 | 17 | 9 | 21 |
| NLP / Orchestrator | 45 | 18 | 13 | 14 |
| Planner | 40 | 16 | 14 | 10 |
| Life Tracker | 57 | 18 | 9 | 30 |
| QA / DevEx / Agent Automation | 41 | 14 | 9 | 18 |
| Voice Chain | 67 | 30 | 12 | 25 |
| Memory / Context DB | 46 | 6 | 18 | 22 |
| Smart Home / Phone / Bank / Wearables | 56 | 7 | 8 | 42 |
| Analytics | 34 | 8 | 4 | 22 |
| Product / One-click UX | 60 | 22 | 6 | 32 |
| Voice / Old Jarvis migration | 11 | 8 | 3 | 0 |
| Desktop Client | 7 | 7 | 0 | 0 |

## Strongest (mostly DONE)
- **Architecture / Microservices, Observability, Desktop Client** — ~100%.
- **Infra/K8s** — k3s stack, Jib builds, ingress/TLS, NetworkPolicy, full obs stack, `/status/report`.
- **PC Control** — real Linux control (apps/volume/MPRIS-media/windows/keys/mouse/scenarios/audit).
- **LLM/Brain** — local Qwen3-14B on GPU, persona, risk-gated tool registry, RAG (cloud fallback intentionally refused by LocalOnlyEnforcer).
- **Voice** — STT/wake-word/Piper neural TTS/WS streaming/language-adaptive.

## Biggest TODO clusters (where the work remains)
- **Smart Home / Wearables / Bank (42 TODO):** static device catalog (no dynamic add/discovery/groups/scenes/history), no weather/sensors, no real bank (manual finance only), wearables read only sleep+steps, **HEALTH_ENTRY queued on phone but not consumed server-side** (SyncPayloadKind lacks it).
- **Product / One-click UX (32 TODO):** many JavaFX screens unbuilt (onboarding, tray, command palette, per-diagnostic screens, version/rollback, profiles); unified `jarvis` CLI has only `up` (no status/logs/backup/restore/update).
- **Life Tracker (30 TODO):** habit/weight/mood/steps trackers, CSV bank import, export/import, calendar sync, weekly/tomorrow rollups.
- **Analytics (22 TODO):** the whole insight/forecast/correlation/report/score/widget layer (current analytics = genuine but narrow aggregates).
- **Memory (22 TODO):** typed memory scopes, UI search/edit/delete, export/import, encryption, dedup/expiry (forget-fact + pgvector RAG already DONE).
- **Voice Chain (25 TODO):** noise suppression, echo cancel, barge-in, "повтори/короче/подробнее", TTS voice/speed selection, earcons, audio retention controls.
- **Security (21 TODO):** OWNER/GUEST/SERVICE roles, scope permissions, runtime permission prompts, security headers, session timeout/lock, panic mode, kid rotation, per-jti access revocation.

## Notable corrections found during review
- Music control **is MPRIS/playerctl-based** (not just media keys).
- **gitleaks + trivy ARE wired** in `.github/workflows/security-and-build.yml` (SBOM still absent).
- **MemoryForgetService** (3-layer wipe + tombstone + audit) exists → "forget a fact" is DONE.
- "External LLM API fallback" is **intentionally not built** (LocalOnlyEnforcer) — by design, not a miss.
- Namespaces consolidated to one (`jarvis-prod`) vs the planned four.

> Per-story ID buckets per epic are available from the review run; ask to expand any epic
> into its exact DONE/PARTIAL/TODO id list.
