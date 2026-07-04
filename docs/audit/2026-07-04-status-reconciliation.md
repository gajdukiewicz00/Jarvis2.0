# Jarvis 2.0 — Status Reconciliation (2026-07-04)

Audit date: **2026-07-04**. Method: static code audit (grep/count-based, read-only —
no cluster access, no builds) of the working tree at
`/home/kwaqa/Jarvis/Jarvis2.0`. This document is the **single source of truth for
current backlog status**, superseding the headline numbers in
[`docs/USER_STORIES_STATUS.md`](../USER_STORIES_STATUS.md) (2026-06-05 pass) without
deleting that file — it remains useful for its per-epic notes and "notable
corrections" list. Cross-references: [`docs/USER_STORIES_STATUS.md`](../USER_STORIES_STATUS.md),
[`docs/roadmap.md`](../roadmap.md), [`docs/PROJECT_REPORT.md`](../PROJECT_REPORT.md).

The master backlog itself is **not** in this repository — it lives at
`~/Downloads/jarvis_user_stories_backlog_701.md` on the operator's machine.

---

## (a) Overall tri-state tally

| Status | Count | Share |
|---|---:|---:|
| ✅ DONE | **334** | **47%** |
| 🟡 PARTIAL | **135** | **19%** |
| ⬜ TODO | **239** | **34%** |
| **Total** | **708** | **100%** |

Arithmetic check: `47 + 19 + 34 = 100` ✓ and `334 + 135 + 239 = 708` ✓.

**DONE + PARTIAL = 469 / 708 = 66%** of the backlog is real (built, at least
partially) — up from the ~60% figure in the prior pass.

### Reconciling against the 2026-06-05 pass

`docs/USER_STORIES_STATUS.md` headlines **701 stories** at **41% / 20% / 40%**
(DONE/PARTIAL/TODO, quoting its own header numbers ~289/~137/~283). Its own per-epic
table, however, sums to **708** on the Total column — the same total this audit
uses. That is a **~7-story count drift** between the file's headline (701) and its
own per-epic total (708), which the file itself flags with the caveat:

> "Slight count drift (~+8) from overlapping section ranges; treat as directional."

This audit adopts the same caveat and the same 708 denominator (the per-epic sum),
since that is the reproducible figure — re-summing the Total column of the table in
section (b) below reproduces 708 exactly. One additional inconsistency was found and
fixed while reconciling: the prior file's **Smart Home / Phone / Bank / Wearables**
row lists Total=56 but its own DONE+PARTIAL+TODO cells (7+8+42) sum to 57 — a
one-story internal inconsistency baked into the earlier pass. This audit's
corrected row (56 = 14+9+33) is internally consistent.

Net movement since 2026-06-05 (old exact table sums → this audit, per column):

- DONE: 289 → 334 (**+45**)
- PARTIAL: 137 → 135 (**−2**)
- TODO: 283 → 239 (**−44**)

(+45 − 2 − 44 = −1, which is exactly the one-story Smart Home inconsistency
corrected above — the reconciliation is otherwise exact.)

---

## (b) Per-epic corrected table (17 epics)

| Epic | Total | ✅ DONE | 🟡 PARTIAL | ⬜ TODO | Δ vs 2026-06-05 (D/P/T) |
|---|---:|---:|---:|---:|---|
| Architecture / Microservices | 21 | 19 | 1 | 1 | 0 / 0 / 0 |
| Observability | 5 | 5 | 0 | 0 | 0 / 0 / 0 |
| Infra / K8s | 50 | 30 | 8 | 12 | +2 / −1 / −1 |
| LLM / Agent Brain | 55 | 21 | 9 | 25 | −6 / −5 / +11 |
| PC Control | 66 | 39 | 8 | 19 | 0 / 0 / 0 |
| Security | 47 | 22 | 12 | 13 | +5 / +3 / −8 |
| NLP / Orchestrator | 45 | 15 | 12 | 18 | −3 / −1 / +4 |
| Planner | 40 | 21 | 13 | 6 | +5 / −1 / −4 |
| Life Tracker | 57 | 26 | 10 | 21 | +8 / +1 / −9 |
| QA / DevEx | 41 | 16 | 8 | 17 | +2 / −1 / −1 |
| Voice Chain | 67 | 30 | 13 | 24 | 0 / +1 / −1 |
| Memory | 46 | 20 | 16 | 10 | +14 / −2 / −12 |
| Smart Home / Bank / Wearables | 56 | 14 | 9 | 33 | +7 / +1 / −9 (base row corrected 57→56, see §a) |
| Analytics | 34 | 12 | 7 | 15 | +4 / +3 / −7 |
| Product / One-click UX | 60 | 29 | 6 | 25 | +7 / 0 / −7 |
| Voice / Old Jarvis migration | 11 | 8 | 3 | 0 | 0 / 0 / 0 |
| Desktop Client | 7 | 7 | 0 | 0 | 0 / 0 / 0 |
| **Total** | **708** | **334** | **135** | **239** | +45 / −2 / −44 |

Column totals verified by direct sum (see "Verification" section below). Six epics
are unchanged from the prior pass (Architecture, Observability, PC Control, Voice
Chain's DONE, Voice-migration, Desktop Client) — these were already accurately
classified. The largest single correction is **LLM / Agent Brain** (−6 DONE / +11
TODO), followed by **Memory** (+14 DONE / −12 TODO) and **Security** (+5 DONE / −8
TODO); all three moved in response to closer code-level verification of what is
actually wired end-to-end vs. merely scaffolded. Per the same-caveat as
`USER_STORIES_STATUS.md`, treat epic-level deltas as directional, not a
story-by-story diff (no per-ID list was re-run for this pass).

---

## (c) Three bonus modules — not in any backlog

These three services exist in the codebase but map to **no user story ID** in the
701/708-story backlog; they were built as extensions beyond the original scope.
Test counts below were verified directly (not estimated):

| Module | Est. completeness | Test files | `@Test` count | Verified with |
|---|---:|---:|---:|---|
| `agent-service` (role swarm) | ~85% real | 12 | **51** | `grep -rc "@Test" apps/agent-service/src/test --include="*.java" \| awk -F: '{s+=$2} END{print s}'` |
| `media-service` (RU dubbing) | ~55% real | 20 | **80** | same pattern against `apps/media-service/src/test` |
| `vision-security-service` | ~65% real | 31 | **134** | same pattern against `apps/vision-security-service/src/test` |

- **agent-service** — task queue, permission guard, panic-checkpointed executor,
  swarm coordinator. Genuinely wired (own `AgentTaskStore`/`InMemoryAgentTaskStore`,
  `AgentActionGuard`, audit trail). See `docs/ops/state-durability.md` item 3 for its
  restart-durability gap.
- **media-service** — real `ffmpeg`/`ffprobe` integration confirmed
  (`RealFFmpegClient.java`, `RealFFprobeClient.java` exist alongside mock
  counterparts, selected by `media.*.mode` properties). However, the three pipeline
  stages that matter most for "RU dubbing" quality are **100% mock**:
  - `MockAsrProvider` — only `AsrProvider` implementation found in the codebase.
  - `MockTranslationProvider` — only `TranslationProvider` implementation found.
  - `NeutralRussianTtsProvider` — its own javadoc says *"placeholder synthesis"* and
    the output marker is literally `"MOCK-TTS voice=..."` written to a text file
    instead of real audio.
  No `Real*` counterpart exists for ASR, translation, or TTS today.
- **vision-security-service** — 134 tests is the largest suite of the three, but the
  service is **host-only**: `infra/k8s/base/vision-security-service/` contains only
  `service.yaml` + `endpoints.yaml` (a `Service`/`Endpoints` pair pointing at a
  host-local process), no `Deployment`. It is not a running pod in `jarvis-prod`.
  Confirmed also by `docs/roadmap.md`: *"Vision — real code ... but
  workstation-local; not in `jarvis-prod`, gated by `VISION_SECURITY_ENABLED` +
  local-bridge."*

---

## (d) "Code-DONE" vs "verified-live"

A story counted DONE in section (b) means the implementation exists and compiles —
**not** that it has been exercised end-to-end against the live stack. The
human-facing layer in particular is implemented in code but not verified live:

- **Voice E2E** — STT/wake-word/Piper TTS/WS streaming code exists; full
  microphone-to-speaker round trip against the live cluster has not been re-verified
  this pass.
- **Desktop GUI visuals** — JavaFX screens exist in source; visual/interaction
  verification (screenshots, click-through) not re-run this pass.
- **Real PC actions** — the in-cluster `pc-control` pod runs with
  `PC_CONTROL_STUB_MODE=true` hardcoded in **both** k8s trees
  (`infra/k8s/base/pc-control/deployment.yaml:48` and
  `k8s/base/pc-control/deployment.yaml:48`, both `value: "true"`). Real control only
  happens via the **host** JavaFX/agent path, matching `docs/roadmap.md`: *"Desktop
  control — `pc-control` runs in stub mode in-cluster; real control is the host
  JavaFX/agent path."*
- **Android pairing** — code exists; live pairing against a physical device not
  re-verified this pass.

Net: roughly **15-20%** of the backlog is headlessly demonstrable (API-level,
scriptable, no human sensory loop required) in a way that's been actually re-run
recently; the rest of the 66% DONE+PARTIAL figure is "verified by reading the code
and its tests," which is a materially weaker claim than "watched it work."

### Track-A insight: host-JVM path bypasses k3s entirely

`scripts/runtime-up.sh` starts every backend service (`security-service`,
`user-profile`, `nlp-service`, `orchestrator`, `voice-gateway`, `pc-control`,
`vision-security-service`, `smart-home-service`, `life-tracker`,
`analytics-service`, `api-gateway`, `planner-service`, plus optionally
`llm-server`) as plain **host JVM processes**, not k8s pods. Combined with the
`host-model-daemon` (Qwen brain, `:18080`) and `host-tts-daemon` (Piper neural TTS,
`:18090`) already running as **systemd `--user` services with `loginctl
enable-linger`** (survive reboot without a login session — see
`docs/JARVIS_ALIVE.md`), this means **voice, desktop, and real (non-stub) PC control
are demonstrable entirely without k3s being up**. This is the practical mitigation
for risk (e) below while the cluster is down.

---

## (e) Key operational risk: dual k8s trees

Two parallel Kubernetes manifest trees exist in this repo:

- **`infra/k8s/`** — the canonical tree (confirmed canonical by
  `docs/roadmap.md`: *"Canonical deployment path: `infra/k8s/overlays/prod` renders
  to 137 objects and matches the live namespace; `k8s/` carries a LEGACY banner."*).
  Its `overlays/prod/kustomization.yaml` pins every image to `newTag: local`
  (11 services observed); `base/kustomization.yaml` pins to `newTag: 1.0.0`.
  Neither carries the movie-upgrade-specific tags.
- **`k8s/`** — the legacy tree, still present, and **not** purely inert: it
  hardcodes movie-upgrade image tags directly in two deployments —
  `k8s/base/agent-service/deployment.yaml:32`
  (`localhost:5000/jarvis/agent-service:movie1`) and
  `k8s/base/media-service/deployment.yaml:32`
  (`localhost:5000/jarvis/media-service:movie1`).

**The risk**: because the canonical tree's `newTag: local`/`1.0.0` does not know
about the `movie1` builds, a reboot or a fresh `kubectl apply -k
infra/k8s/overlays/prod` re-applies the generic tag and **silently reverts** those
two services to whatever image `:local`/`1.0.0` resolves to — wiping the
movie-upgrade build without any error. This is the mechanism behind the "reboot
wiped tags" failure class.

**Status at time of this audit**: the cluster is currently **DOWN** after a host
reboot. Remediation is tracked as an in-progress, multi-step fix (**REPRO-1..5**,
2026-07-04) to consolidate the two trees onto one canonical, correctly-tagged
source of truth, plus a companion **REPRO-7** reboot auto-recovery script. This
document itself is tracked as **DOC-1 + REPRO-8** (status source-of-truth +
state-durability doc). See `docs/ops/state-durability.md` for the companion
in-memory-state risk register (a related but distinct durability problem — pod
*data* loss on restart, rather than pod *image* drift on re-apply).

---

## Verification (re-run to confirm this document)

```bash
# (a)/(b) arithmetic
python3 -c "print(47+19+34, 334+135+239)"   # -> 100 708

# (c) bonus-module test counts
for m in agent-service media-service vision-security-service; do
  echo -n "$m: "
  grep -rc "@Test" "apps/$m/src/test" --include="*.java" | awk -F: '{s+=$2} END{print s}'
done
# -> agent-service: 51 / media-service: 80 / vision-security-service: 134

# (c) media-service mock-only ASR/translation providers
grep -rl "implements AsrProvider" apps/media-service/src/main --include="*.java"
grep -rl "implements TranslationProvider" apps/media-service/src/main --include="*.java"
# -> only MockAsrProvider.java / MockTranslationProvider.java in each case

# (d) PC control stub flag
grep -n "PC_CONTROL_STUB_MODE" infra/k8s/base/pc-control/deployment.yaml k8s/base/pc-control/deployment.yaml

# (e) dual-tree tag evidence
grep -n "newTag" infra/k8s/overlays/prod/kustomization.yaml infra/k8s/base/kustomization.yaml
grep -n "image:.*movie" k8s/base/agent-service/deployment.yaml k8s/base/media-service/deployment.yaml
```

All commands above were re-run as part of producing this document; outputs match
the numbers stated in sections (a)-(e).
