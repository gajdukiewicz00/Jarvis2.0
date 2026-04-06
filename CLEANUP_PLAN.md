# Cleanup Plan: Jarvis

## Goal

Reduce confusion by making the repository describe the system as it actually exists today.

This plan does **not** assume large rewrites. It focuses first on source-of-truth cleanup, naming honesty, and removing misleading artifacts.

Confidence: **high**

## Highest-Value Problems

| Problem | Why It Is A Problem | Why It Confuses People | Evidence | Recommended Fix | Priority | Confidence |
| --- | --- | --- | --- | --- | --- | --- |
| Too many overlapping truth documents | different files describe different "Jarvis" states | the owner reads docs that all sound authoritative but disagree | `README.md`, `docs/architecture.md`, `docs/BACKEND_STATUS.md`, `k8s/README.md`, archive docs | define one runtime truth doc set and demote the rest | P0 | high |
| Optional AI is described too centrally | docs make AI sound core while runtime keeps it optional | project feels more AI-complete than it is | runtime flags and prod overlay scale-to-zero vs README/architecture language | rewrite top-level docs so AI is clearly "real but optional" | P0 | high |
| `analytics-service` is misrepresented | diagrams imply its own DB-backed analytics store | hides that analytics currently depends on `life-tracker` data over HTTP | `README.md`, `docs/architecture.md`, `apps/analytics-service/pom.xml` | correct all diagrams and service descriptions | P0 | high |
| Planner placeholders are not labeled as placeholders in top-level docs | planner sounds more mature than its code | creates false confidence in AI/personalization readiness | planner client and service placeholder methods | mark placeholder integrations as experimental or partial in docs and code comments | P0 | high |
| RabbitMQ and Kafka create false architecture weight | deps/configs look like a real event-driven platform | readers assume real message-bus architecture exists | service POMs and configs, but no real listeners/producers in core flows | either remove them from top-level stack claims or label them "inactive hooks" | P0 | high |
| Dead gateway security client | stale code suggests auth validation flow that does not exist | future readers may trust a broken path | `apps/api-gateway/src/main/java/org/jarvis/apigateway/client/SecurityServiceClient.java` | delete or clearly mark legacy; prefer gateway-local JWT validation as the only truth | P1 | high |
| Hidden memory-service URL default in gateway | config is only partly declared | optional integration looks more explicit than it is | `MemoryServiceClient.java` vs `application.yaml` | either declare `services.memory.url` explicitly or remove the client until needed | P1 | high |
| Android app looks more productized than it is | repo has a mobile module, but product reality is scaffold-level | owners and reviewers overestimate client completeness | `apps/mobile-client`, manifest only declares `MainActivity` | mark mobile as scaffold in README and docs | P1 | high |
| Archive docs compete with live docs | old specs are easy to mistake for current behavior | raises the cost of understanding the repo | `docs/_archive/` | add a single archive index with "historical only" warning; stop linking archive docs from live docs | P1 | high |
| Root zero-byte `analytics-service` file | misleading repo artifact | looks like a module or script but is neither | repo root `analytics-service` file | delete after confirmation or mark as accidental leftover | P2 | high |
| Hard-coded local smoke ports | local verification breaks when common ports are occupied | makes healthy code look broken on developer machines | `scripts/runtime-smoke.sh` uses `5000` and local gateway runtime uses `8080` | make smoke ports configurable with defaults and conflict detection | P1 | high |

## What Should Become The Single Source Of Truth

Recommended doc hierarchy:

### Top-Level Truth

- `PROJECT_REALITY.md`
- `ARCHITECTURE_EXPLAINED.md`
- `SERVICE_CATALOG.md`
- `TECH_STACK_REALITY.md`
- `MVP_VS_VISION.md`

Role:

- explain what exists today
- classify core vs optional vs partial vs legacy
- describe actual runtime and service wiring

Confidence: **high**

### Operational Truth

- `docs/BACKEND_STATUS.md`
- `k8s/README.md`
- deployment runbooks

Role:

- deployment status
- rollout posture
- TLS migration status
- smoke and preflight policy

Confidence: **high**

### Historical / Design Material

- `docs/_archive/**`

Role:

- keep for history only
- do not let it compete with live runtime truth

Confidence: **high**

## What To Rewrite In README

Recommended README shape:

1. One-paragraph plain description of Jarvis as it exists today.
2. One short table listing:
   - core runtime services
   - optional AI services
   - partial clients/features
3. A short "Current Reality" section:
   - default runtime works without LLM and memory
   - AI stack is optional
   - analytics is an HTTP aggregator
   - mobile is partial
4. Links to the five reality documents.
5. Keep deeper architecture and deployment detail out of the README.

Confidence: **high**

## What To Mark As Experimental

These should stay in the repo, but should be labeled clearly:

- `llm-service`
- `llm-server`
- `memory-service`
- `embedding-service`
- planner AI enhancement paths
- planner fake integration clients
- `pc-control` security monitoring
- desktop wake-word path
- Kafka/RabbitMQ hooks

Recommended action:

- add "optional" or "experimental" wording in service docs and module READMEs
- avoid listing these as unconditional product capabilities

Confidence: **high**

## What To Move Into Future Roadmap Language

These should not be written as current product facts:

- always-on local LLM assistant
- always-on long-term memory
- fully real smart-home hardware ecosystem
- advanced AI planner recommendations
- Android client parity
- event-driven Kafka/RabbitMQ platform story
- production-grade biometric workstation monitoring

Confidence: **high**

## What Could Be Removed

Only remove these after a quick owner confirmation:

- root zero-byte `analytics-service` file
- unused gateway `SecurityServiceClient`
- unused legacy command/scenario artifacts if they have no migration value

Confidence: **medium-high**

Why not immediate "delete everything":

- the repo is already complex and possibly dirty
- some legacy files may still be serving as migration references

## Code-Level Cleanup Targets

| Target | Current Problem | Fix | Priority | Confidence |
| --- | --- | --- | --- | --- |
| `apps/planner-service/src/main/java/.../UserProfileClient.java` | fake goals data | either implement real DTO contract or rename to explicit stub | P0 | high |
| `apps/planner-service/src/main/java/.../LifeTrackerClient.java` | only health-check placeholder | either implement real planner data contract or mark unsupported | P0 | high |
| `apps/planner-service/src/main/java/.../LlmServiceClient.java` | returns plan text unchanged | either implement real LLM call or remove from "implemented" claims | P0 | high |
| `apps/api-gateway/src/main/java/.../SecurityServiceClient.java` | dead client to nonexistent endpoint | delete or mark legacy and unused | P1 | high |
| `apps/mobile-client/app/src/main/AndroidManifest.xml` | settings screen not wired | either wire it or remove misleading dead activity | P1 | high |
| `scripts/runtime-smoke.sh` | fixed ports and fragile local assumptions | make ports configurable and detect collisions early | P1 | high |

## Documentation Cleanup Sequence

1. Treat the new reality docs as the canonical explanation layer.
2. Rewrite `README.md` into a short honest overview.
3. Update `docs/architecture.md` so it matches real service wiring:
   - analytics via `life-tracker`
   - AI optional, not central by default
   - HTTP-first messaging truth
4. Keep `docs/BACKEND_STATUS.md` focused on deployment and verification only.
5. Add a visible notice at the top of `docs/_archive/` that it is historical.

Confidence: **high**

## Practical End State

After cleanup, a new reader should understand Jarvis in this order:

1. what the default runtime actually starts
2. which services are core
3. which services are optional AI layers
4. which features are partial or placeholder
5. which docs are live truth versus historical context

That is the fastest path from "confusing impressive repo" to "understandable system."

Confidence: **high**
