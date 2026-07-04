# Jarvis Memory Architecture & Local Model Routing

Last verified: 2026-06-06 against `jarvis-prod` (k3s). Honest status — "working /
disabled / planned" is marked explicitly. No fake claims.

## 1. Memory architecture

Jarvis keeps memory in **two Postgres-backed stores**, both embedded with the
same 384-dim model (pgvector `vector(384)`):

| Store | Table | Written by | Search |
|-------|-------|-----------|--------|
| **chunk-store** | `memory_chunk` | conversation ingestion + host alive-loop screen-context (`jarvis-memory-sync.sh`) | **semantic** (pgvector `<=>`), lexical fallback |
| **note-store** | `memory_notes` (`MemoryNoteEntity`) | `POST /api/v1/memory/notes`, Obsidian indexer (`jarvis-obsidian.py index`) | **semantic** (pgvector `<=>` via JdbcTemplate), keyword fallback |

### Search endpoints
- `POST /api/v1/memory/search` — **semantic** over chunk-store only
  (`retrievalMode = semantic | lexical-fallback`). Used by `llm-service` RAG.
- `POST /api/v1/memory/search/unified` — **dual-source** (NEW). Merges:
  - chunk-store hits → `source: "conversation"` (includes screen-context, which is
    ingested as conversation chunks), with `score` + `createdAt`;
  - note-store hits → **semantic-first** (`noteSearchMode: semantic`): the query is
    embedded and notes are ranked by pgvector cosine distance (≤0.75) via JdbcTemplate;
    falls back to keyword (title/body LIKE) if embeddings are unavailable. Each hit is
    `source: "obsidian"` (vault-mirrored) or `"memory"`, with
    `title`, `path` (Obsidian file), `category`, `snippet`, `createdAt`.
  Degrades gracefully: if one store errors, the other still returns.

### Source tags returned
`conversation` (chat + screen_context chunks) · `obsidian` (vault notes) ·
`memory` (explicit notes without a vault path).

### Embeddings
`embedding-service` (in-cluster, ready) produces 384-dim vectors. If it is
unavailable, chunk search falls back to deterministic lexical ranking and note
search is keyword-only — **no crash, degraded mode**.

### Obsidian ↔ memory
`scripts/jarvis-obsidian.py` owns `~/JarvisVault` (host filesystem; the in-cluster
`ObsidianVaultWriter` cannot mount the host vault — Kyverno blocks hostPath).
`jarvis obsidian index` posts each note to `/api/v1/memory/notes` (category
`PROJECTS`), which persists to Postgres → embeds → becomes findable via
`/search/unified`. Secret-shaped content is redacted before any write (11 unit
tests, `scripts/tests/test_jarvis_obsidian.py`).

## 2. Local multi-model routing — exact status

GPU: one consumer NVIDIA (RTX 5070, 12 GB). Strategy: **one heavy model resident
(14B), everything else light or rule-based.** Do not load multiple large models.

| Role | Provider | Port / location | Status |
|------|----------|-----------------|--------|
| **main_chat / reasoning / summarizer** | llama.cpp `qwen3-14b-q4_k_m` | host `:18080` (host-model-daemon) | **WORKING** (GPU, ~24 tok/s) |
| **fast_intent** | rule-based `RuleBasedNlpService` | nlp-service (in-cluster) | **WORKING** (no model, instant) |
| **embedding** | embedding-service (384-dim) | in-cluster | **WORKING** |
| **tts** | Piper (`en_GB-alan` / `ru_RU-dmitri`) | host `:18090` (host-tts-daemon) | **WORKING** |
| **stt** | desktop client (voice-gateway + JavaFX) | client-side | **PARTIAL** (desktop-bound) |
| **coding** | llama.cpp (separate model) | host `:18081` | **DISABLED** (daemon not running) |
| **router** | small classifier | host `:18082` | **DISABLED** (daemon not running) |

### Critical config rule
`llm-service` env **must** be:
- `LLM_SERVER_URL=http://host-model-daemon.jarvis-prod.svc.cluster.local:18080`
- `JARVIS_HOST_DAEMON_ENABLED=false`

`HOST_DAEMON_ENABLED=true` switches llm-service to multi-port mode and probes
`:18081` (coding) + `:18082` (router). Those daemons are **not running**, so the
pod fails readiness (0/1). Keep it `false` until those models are actually served.
`summarizer` and `coding` requests currently route to the main 14B (single-model
fallback) — adequate on 12 GB VRAM without loading extra models.

### Fallback routing
- main_chat down → `llm-service` returns degraded; orchestrator answers with
  persona phrase + rule-based intents still execute.
- embedding down → lexical/keyword search.
- tts down → silent (text only).
- host-model-daemon endpoint reset to placeholder → `jarvis doctor` flags it loudly;
  `scripts/jarvis-host-endpoint-check.sh --fix` re-wires it (also run by `jarvis up`).

### Lazy / optional loading
Coding/router models are **opt-in**: start the daemons on `:18081/:18082`, then set
`HOST_DAEMON_ENABLED=true`. Until then they are honestly DISABLED, not pretended.
