# Local Obsidian AI Memory Stack — Design

- Status: Draft (proposal)
- Date: 2026-05-09
- Scope: model selection, retrieval pipeline, vault structure, persistence, API, security
- Supersedes: nothing (extends [ADR-0010](ADR/ADR-0010-obsidian-memory.md))
- Open decisions: see § Open Questions

This document records the verified evidence and the design that follows from it. Anything marked **(unverified)** has not been confirmed against a primary source and must not be acted on without further checking.

---

## 1. Context and constraints (re-stated for the record)

- Local-first. **No** user data leaves the host. No cloud APIs for inference, embeddings, or reranking.
- Hardware: Linux desktop, **RTX 5070 12 GB VRAM**, with IDE / browser / Docker / Kafka / backend running concurrently.
- No Chinese or Russian models. (Note: the current `llm-service` runtime ships `qwen2.5-3b-instruct-q4_k_m` per [AiRuntimeStatusService](../../apps/llm-service/src/main/java/org/jarvis/llm/service/AiRuntimeStatusService.java); this violates the rule and is the **primary deviation** to address.)
- Each large model on disk should stay roughly under 50 GB.
- Preferred local format: GGUF Q4_K_M / Q5_K_M.
- Lazy loading / model switching is acceptable; not all models need to be hot at once.

---

## 2. Repo state — what already exists

Inventory derived from a read-only audit of `/home/kwaqa/Jarvis/Jarvis2.0` on 2026-05-09. File paths are the evidence.

| Concern | Status | Evidence |
|---|---|---|
| `memory-service` module | exists, production-grade | [apps/memory-service](../../apps/memory-service) |
| Postgres + pgvector | wired, with Flyway V1–V5 | [V2__create_memory_chunk.sql](../../apps/memory-service/src/main/resources/db/migration/V2__create_memory_chunk.sql) creates `vector(384)` + `ivfflat`; [V5__add_memory_notes.sql](../../apps/memory-service/src/main/resources/db/migration/V5__add_memory_notes.sql) adds notes table |
| Obsidian writer / renderer / forget flow | implemented per ADR-0010 | [obsidian/](../../apps/memory-service/src/main/java/org/jarvis/memory/obsidian/) |
| Embedding service | external Python sidecar at `:5001` | [EmbeddingClient.java](../../apps/memory-service/src/main/java/org/jarvis/memory/service/EmbeddingClient.java), [embedding-service-py](../../apps/embedding-service-py/) |
| Audit projector | Kafka → `audit_events` | [V4__add_audit_events.sql](../../apps/memory-service/src/main/resources/db/migration/V4__add_audit_events.sql), [audit/](../../apps/memory-service/src/main/java/org/jarvis/memory/audit/) |
| Reranker | **not present** | grep finds no rerank/cross-encoder code |
| LLM router config | only the host-daemon channels (main / coding / router) at `:18080-18082`, all currently Qwen | [LlmLifecycleManager.java](../../apps/llm-service/src/main/java/org/jarvis/llm/service/LlmLifecycleManager.java), [llm-service application.yml](../../apps/llm-service/src/main/resources/application.yml) |
| Local-only enforcement | partial — `JARVIS_LLM_LOCAL_ONLY` flag exists in llm-service | [application.yml](../../apps/llm-service/src/main/resources/application.yml) |
| Vault layout | hybrid: ADR-0010 (`03_Memory/`, `06_System/`) + later additions (`00_Inbox/`, `01_Daily/`) | [memory-service application.yml lines 9–14](../../apps/memory-service/src/main/resources/application.yml#L9-L14) |

**Net:** the scaffolding is already there. The work this design proposes is **swap models, add reranker, add user-side vault folders, tighten egress, and capture retrieval-time audit** — not greenfield.

---

## 3. Model verification (PHASE 1)

All facts below come from the model card at the cited URL on 2026-05-09. Anything not on the page is marked.

### 3.1 Recommended stack

| Role | Model | License | Origin | Local | Verified disk size | Runtime |
|---|---|---|---|---|---|---|
| Universal + vision + memory brain | **`google/gemma-3-27b-it`** | Gemma terms (Google custom, not Apache) | Google (US) | yes | **17 GB** Q4_K_M (Ollama default) | Ollama / llama.cpp / vLLM / SGLang / LM Studio |
| Coding brain | **`mistralai/Devstral-Small-2507`** (Devstral Small 1.1, 24B) | **Apache 2.0** | Mistral AI (France) | yes | **14.3 GB** Q4_K_M, **16.8 GB** Q5_K_M (Mistral official GGUF repo) | Ollama / llama.cpp / vLLM / Mistral-inference / Transformers / LM Studio |
| Embeddings (English) | **`nomic-ai/nomic-embed-text-v1.5`** | **Apache 2.0** | Nomic AI (US) — *not stated on the model card itself; verified separately* | yes | **274 MB** Ollama tag, ~140 MB raw safetensors (137M params, 0.1B) | sentence-transformers / Transformers / Transformers.js / Infinity / Ollama |
| Reranker | **`ibm-granite/granite-embedding-reranker-english-r2`** | **Apache 2.0** | IBM Research (US) | yes | not stated on card; 149M params → ~300–600 MB at fp16/fp32 (unverified, infer from param count) | sentence-transformers (CrossEncoder) / Transformers / Text Embeddings Inference (TEI) |

Sources: [Gemma 3 27B IT](https://huggingface.co/google/gemma-3-27b-it), [Devstral-Small-2507](https://huggingface.co/mistralai/Devstral-Small-2507), [Devstral-Small-2507 GGUF](https://huggingface.co/mistralai/Devstral-Small-2507_gguf), [nomic-embed-text-v1.5](https://huggingface.co/nomic-ai/nomic-embed-text-v1.5), [granite-embedding-reranker-english-r2](https://huggingface.co/ibm-granite/granite-embedding-reranker-english-r2), [Ollama gemma3:27b](https://ollama.com/library/gemma3:27b), [Ollama devstral](https://ollama.com/library/devstral), [Ollama nomic-embed-text](https://ollama.com/library/nomic-embed-text).

### 3.2 Notable facts the prompt under-specified

- **Gemma 3 license is not Apache.** It is the *Gemma terms* (`license: gemma`) with a Prohibited Use Policy. This is "open weights with a use policy", not OSI-approved. If pure OSI compliance is required, Gemma 3 is not a fit and we'd need to fall back to a Mistral or IBM Granite vision model.
- **Gemma 3 27B is multimodal**: text + image input → text output, with images "normalized to 896×896 and encoded to 256 tokens each" (per model card). This is what makes it the "vision brain".
- **Gemma 3 27B context: 128K** input / 8 192 output (per model card).
- **Devstral Small 1.1** is the latest as of model card; 24B params, 128K context, finetuned from `Mistral-Small-3.1-24B-Base-2503` (vision encoder removed). Officially recommended for *agentic coding*.
- **nomic-embed-text-v1.5**: 768-dim native, **Matryoshka** — usable at 512/256/128/64; 8 192-token context per HF card. Ollama's default `nomic-embed-text` build runs at **2K context window** — for chunks larger than ~1.5 KB you must use llama.cpp or sentence-transformers with the longer ctx.
- **nomic-embed-text-v1.5 is English**. Per the card: trained on English text. The current vault embedding model `multilingual-e5-small` (384 dims) is multilingual including Russian. Since `RussianLanguageEnforcer` is wired into [llm-service](../../apps/llm-service/src/main/java/org/jarvis/llm/service/RussianLanguageEnforcer.java), **switching to nomic will degrade retrieval quality for Russian content**. See § 8 for the recommended path.
- **Granite reranker is English-only by name.** For multilingual text consider IBM's separate multilingual embedding line — *not verified in this pass, do not assume*.

### 3.3 Hardware reality vs. model size — **important**

The user has 12 GB VRAM. At Q4_K_M:

- Gemma 3 27B = 17 GB → **does not fit fully on 12 GB VRAM**.
- Devstral 24B = 14.3 GB → **does not fit fully on 12 GB VRAM**.

Both work via partial GPU offload (`--n-gpu-layers ~30-40` in llama.cpp, or letting Ollama auto-split). Throughput is then bound by CPU / RAM bandwidth for the offloaded layers and will be substantially slower than fully-on-GPU. Concrete tokens/sec figures are **not verified** here and depend on RAM speed / CPU / context length.

If the desktop must keep IDE / browser / Docker / Kafka usable:

- **Do not run Gemma + Devstral at the same time.** Use lazy switching (Ollama unloads when idle; llama.cpp can be wrapped by a lifecycle manager — `LlmLifecycleManager.java` already exists for exactly this).
- Reserve VRAM headroom (~2 GB) for the embedding model if it's pinned to GPU; otherwise embed on CPU (fine at 137M params).

If 12 GB ends up being the bottleneck, smaller-but-still-strong fallbacks:

- **Gemma 3 12B IT** (8.1 GB Q4_K_M per Ollama, *not re-verified in this pass — confirm before adopting*) — fits on GPU with headroom. Same vision capability per Google card.
- **Devstral Small 24B at Q3_K_M / Q3_K_L** — sub-12 GB but quality drop is real.

These fallbacks are **proposed, not verified** in this design pass.

### 3.4 Risks / unknowns

- Gemma terms compatibility with downstream redistribution: **unverified for your use case**. If you ever ship a docker image embedding the weights, re-read the Gemma terms.
- Granite reranker disk size and exact weight precision: **not on the model card**; inferred from 149M params.
- Nomic-embed-text-v1.5 Ollama vs llama.cpp ctx-length divergence: real, requires explicit configuration.
- All Russian-language quality claims: untested in this design pass.

---

## 4. Architecture

### 4.1 Data flow

```
                           ┌──────────────────────────────────┐
                           │  Desktop (JavaFX) / Voice / API  │
                           └──────────────┬───────────────────┘
                                          │  STT (local)
                                          ▼
                           ┌──────────────────────────────────┐
                           │   orchestrator + intent router   │
                           └──┬─────────────┬─────────────┬───┘
                              │             │             │
                deterministic │      memory │      coding │  vision/img
                  classifier  │     question│    question │  question
                              ▼             ▼             ▼
                        small-model   memory-service   memory-service
                          router       /search          /search (no img)
                                          │                │
                                          ▼                ▼
                                 ┌────────────────┐  ┌────────────┐
                                 │  embeddings    │  │ embeddings │
                                 │  (local svc)   │  │  (local)   │
                                 └────────┬───────┘  └─────┬──────┘
                                          ▼                ▼
                                 ┌────────────────────────────────┐
                                 │  PostgreSQL + pgvector          │
                                 │   memory_note / memory_chunk    │
                                 └──────────────┬──────────────────┘
                                                ▼
                                 ┌────────────────────────────────┐
                                 │  Reranker (Granite, local)      │
                                 └──────────────┬──────────────────┘
                                                ▼
                              ┌──────────────────────────────────┐
                              │   LLM selection (jarvis.ai.models) │
                              │   ┌───────────────────────────┐  │
                              │   │  Gemma 3 27B  (universal+  │  │
                              │   │  vision + personal memory) │  │
                              │   ├───────────────────────────┤  │
                              │   │  Devstral 24B (code/repo)  │  │
                              │   └───────────────────────────┘  │
                              └──────────────────┬──────────────┘
                                                 ▼
                              ┌──────────────────────────────────┐
                              │  Response → user                  │
                              │  Audit row → memory_search_audit  │
                              │  (which notes / chunks / model)   │
                              └──────────────────────────────────┘

      Obsidian Vault (Markdown files) is the canonical human-readable layer.
      memory-service writes notes there atomically (already implemented per ADR-0010).
      A Markdown file edit triggers a re-index path: scanner → chunker → embeddings → pgvector.
```

### 4.2 Components and responsibilities

- **Obsidian Vault** — Markdown source of truth. Lives at `${jarvis.memory.obsidian.vaultPath}`. *Human writes and reads here directly.*
- **memory-service** — owns the index. Runs the scanner, chunker, embedder client, search, write/forget flows, audit emission. Already exists; gains a *retrieval audit* and a *vault scanner* (see § 7.4).
- **embedding-service-py** — local-only; default `:5001`. Currently serves `multilingual-e5-small`. Can be reconfigured to serve `nomic-embed-text-v1.5` *if and when* Russian-language tradeoff is accepted (§ 3.2).
- **reranker-service** *(new, optional)* — local-only Python sidecar serving Granite reranker via TEI or a thin sentence-transformers HTTP wrapper. Bind to `127.0.0.1`. Address: `http://127.0.0.1:5002/rerank` (proposed).
- **llm-service** — keeps host-daemon channels but the binaries served on those ports change from Qwen to Gemma / Devstral. Adds a `ModelRouter` reading `jarvis.ai.models.*`.
- **orchestrator** — already exists; gains a routing rule table (§ 7.5).

### 4.3 Where data lives

- Raw Markdown notes → filesystem at `${vaultPath}` (already).
- Note metadata + content hash → `memory_notes` table (already, V5).
- Chunks → currently `memory_chunk` (V2) for conversation messages; a vault chunker writes to the same table or a new `obsidian_chunks` table (decision: see § 7.4).
- Embeddings → `embedding vector(N)` column inline with the chunk row (already, V2).
- Retrieval audit → currently *implicit* in `audit_events` for write/forget; **proposed**: a dedicated `memory_search_audit` table for read-time retrievals.

### 4.4 Index update on note change

1. File watcher (or scheduled scan) detects mtime/hash change in `${vaultPath}`.
2. Compute new content hash. If unchanged → skip.
3. Delete old chunks for `note_id`. Re-chunk by heading path. Re-embed.
4. Atomic write of new chunk rows. Update `memory_notes.indexed_at`.
5. Emit `MEMORY_REINDEXED` audit event.

Recommended cadence: filesystem watcher (`java.nio.file.WatchService`) + a 60 s debounce + a nightly full reconcile job. The watcher path is *not yet implemented*.

### 4.5 How Jarvis writes a new memory

Already specified by [ADR-0010](ADR/ADR-0010-obsidian-memory.md). The new design adds a **proposal step** (§ 7) so Jarvis-initiated writes go through user confirmation by default — preventing the vault from becoming a junk drawer.

### 4.6 How to avoid the vault becoming junk

Concrete mechanisms:

- **`/api/v1/memory/write-proposal` returns a draft note**, doesn't create files. The user confirms via desktop UI before `write-confirmed` lands the file.
- Frontmatter `confidence`, `source`, and `privacy` are **required**. Notes below a confidence threshold (e.g. 0.6) land in `00_Inbox/` and never auto-merge.
- A nightly retention job reviews `00_Inbox/`: items older than N days with no user action → archived to `99_Archive/` (proposed).
- Jarvis is **read-mostly**. The default Vault posture is read-only for the memory pipeline; the only write path is the confirmed-write API.

### 4.7 Egress lockdown (PHASE 8 preview, full controls in § 9)

- llm-service / memory-service / embedding-service-py / reranker-service all bind to `127.0.0.1`.
- A systemd `IPAddressAllow=localhost` / `IPAddressDeny=any` clause on the unit; or an outbound `nft`/`ufw` rule denying egress for those PIDs.
- `JARVIS_LLM_LOCAL_ONLY=true` (already exists in llm-service) gets a sibling `jarvis.memory.externalNetworkAllowed=false`.

---

## 5. Obsidian vault layout (PHASE 3)

### 5.1 Conflict with ADR-0010 — read this first

ADR-0010 fixed the vault layout as `03_Memory/{Preferences,Habits,Projects,Health,Finance,Time,People}`, `04_Reports/`, `05_Decisions/`, `06_System/deleted-memory-log/`. Your prompt asks for `02_Projects/`, `03_Knowledge/`, `04_Personal/`, `05_Decisions/`, `06_Sources/`, `99_Archive/`.

There is a **direct prefix collision** at `03_*` and `04_*` and a near-collision at `06_*`. We can't silently overwrite an Accepted ADR.

**Proposal:** write **ADR-0011** that explicitly supersedes ADR-0010's "Vault layout" section. Keep ADR-0010's *forget flow*, *frontmatter contract*, and *write order* intact. Renumber the system-written categories.

### 5.2 Proposed unified layout (subject to ADR-0011)

```
JarvisVault/
  00_Inbox/                        ← unconfirmed Jarvis proposals; existing
  01_Daily/                        ← daily journal; existing (jarvis.memory.obsidian.daily-subdir)
  02_Projects/                     ← user project notes
    Jarvis2.0/
      architecture/
      decisions/
      bugs/
      runtime/
  03_Knowledge/                    ← reference material the user curates
    Java/
    AI/
    Security/
    QA/
  04_Personal/
    goals/
    finance/
    health/
  05_Decisions/                    ← was 05_Decisions in ADR-0010 — KEPT
  06_Sources/                      ← external citations / clipped sources
  07_Memory/                       ← system-written memory (was 03_Memory in ADR-0010)
    Preferences/
    Habits/
    Projects/
    Health/
    Finance/
    Time/
    People/
  08_Reports/                      ← was 04_Reports in ADR-0010
  99_Archive/                      ← cold storage
  99_System/                       ← was 06_System in ADR-0010 — kept hidden by name
    deleted-memory-log/
```

**Migration cost** (if ADR-0011 is accepted):

- Rename `03_Memory` → `07_Memory`. Update `MemoryCategory` enum directory mapping.
- Rename `04_Reports` → `08_Reports`.
- Rename `06_System` → `99_System`. Update [application.yml deleted-log-subdir](../../apps/memory-service/src/main/resources/application.yml#L12).
- One Flyway migration to rewrite `memory_notes.vault_relative_path` for existing rows.
- Reindex pass.

If you'd rather **not** churn the existing layout, the alternative is to keep ADR-0010 and treat `02_Projects / 03_Knowledge / 04_Personal / 06_Sources / 99_Archive` as additive new top-levels at the cost of `03_*` and `04_*` having mixed semantics. I do not recommend this — directory prefix is the user's primary navigation hint.

### 5.3 Templates

Five templates, each as a Markdown file with strict YAML frontmatter. Drafts are checked in at [docs/obsidian-vault-templates/](../obsidian-vault-templates/). Contract:

| Field | Type | Required | Notes |
|---|---|---|---|
| `type` | enum | yes | `decision` / `project` / `memory_fact` / `daily` / `source` |
| `created` | ISO-8601 UTC | yes | immutable |
| `updated` | ISO-8601 UTC | yes | bump on edit |
| `tags` | list[str] | yes | use namespace prefixes (`jarvis/memory`, `area/finance`) |
| `source` | str | yes | e.g. `jarvis`, `manual`, `clipped:<url>` |
| `confidence` | float 0..1 | required for `memory_fact`/`source`; optional otherwise | <0.6 → stays in inbox |
| `project` | str | optional | matches a `02_Projects/<name>` folder |
| `privacy` | enum | yes | `local-only` (default) / `share-team` (future) |
| `status` | enum | optional | `active` / `archived` / `superseded` |

This is a **superset** of ADR-0010's frontmatter contract — backward compatible.

---

## 6. Database design (PHASE 4)

### 6.1 Existing tables (unchanged by this design)

- `conversation_message` (V1) — short-term chat history.
- `memory_chunk` (V2) — `embedding vector(384)`, ivfflat (lists=100), tied to `multilingual-e5-small`.
- `session_summary` (V3).
- `audit_events` (V4) — Phase 8 audit projector target.
- `memory_notes` (V5) — Obsidian-backed memory.

### 6.2 Mapping to the prompt's proposed schema

The prompt asks for `obsidian_notes` / `obsidian_chunks` / `obsidian_embeddings` / `memory_audit_log`. Mapping to today's repo:

| Prompt name | Today | Action |
|---|---|---|
| `obsidian_notes` | `memory_notes` | **rename** is invasive; alias via a JPA entity name only if needed |
| `obsidian_chunks` | (does not exist for vault) — `memory_chunk` is for conversation | **add** `memory_note_chunks` table |
| `obsidian_embeddings` | embedding column inline on chunk row | keep inline; **do not split** — pgvector + IVFFlat work best with the embedding co-located on the indexed row |
| `memory_audit_log` | `audit_events` covers writes; nothing covers retrieval | **add** `memory_search_audit` |

### 6.3 Vector dimension decision

The choice is gated by the embedding model:

| Embedding model | Dims | Ctx | Russian | Migration cost |
|---|---|---|---|---|
| `multilingual-e5-small` (today) | 384 | 512 | yes | none |
| `nomic-embed-text-v1.5` | 768 (Matryoshka 64–768) | 8192 | weak | new column / new table; full reindex |
| `granite-embedding-278m-multilingual` (IBM) | **unverified** | **unverified** | **unverified** | **needs verification before recommending** |

**Recommendation:** keep `vector(384)` for now; add a sibling `embedding_768 vector(768)` column behind a feature flag if you choose nomic, and dual-write during a migration window. Do **not** drop the 384-dim column until reindex is verified end-to-end.

### 6.4 Proposed Flyway migrations (drafts, not applied)

**V6 — vault chunk table (only if you split chunks from `memory_chunk`)**

```sql
CREATE TABLE memory_note_chunk (
  id              UUID PRIMARY KEY,
  note_id         UUID NOT NULL REFERENCES memory_notes(id) ON DELETE CASCADE,
  chunk_index     INT  NOT NULL,
  heading_path    TEXT,
  content         TEXT NOT NULL,
  token_count     INT  NOT NULL,
  content_hash    BYTEA NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  embedding       vector(384),
  embedding_model TEXT NOT NULL DEFAULT 'multilingual-e5-small',
  UNIQUE (note_id, chunk_index)
);
CREATE INDEX memory_note_chunk_embedding_idx
  ON memory_note_chunk USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
CREATE INDEX memory_note_chunk_note_id_idx ON memory_note_chunk(note_id);
```

**V7 — retrieval audit**

```sql
CREATE TABLE memory_search_audit (
  id                   UUID PRIMARY KEY,
  query_hash           BYTEA NOT NULL,                -- hash, not the raw query
  query_excerpt        TEXT,                          -- first N chars, redactable; nullable
  selected_model       TEXT NOT NULL,                 -- e.g. 'gemma-3-27b-it-q4'
  retrieved_note_paths JSONB NOT NULL DEFAULT '[]',
  retrieved_chunk_ids  JSONB NOT NULL DEFAULT '[]',
  rerank_used          BOOLEAN NOT NULL DEFAULT false,
  top_k                INT NOT NULL,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX memory_search_audit_created_idx ON memory_search_audit (created_at DESC);
```

`query_excerpt` is gated by the existing `logging.pii.allowQuerySnippet` flag in [memory-service application.yml line 115](../../apps/memory-service/src/main/resources/application.yml#L115).

---

## 7. Memory service API (PHASE 5)

### 7.1 What exists today

From [MemoryController.java](../../apps/memory-service/src/main/java/org/jarvis/memory/controller/MemoryController.java) and [MemoryNoteController.java](../../apps/memory-service/src/main/java/org/jarvis/memory/obsidian/MemoryNoteController.java):

- `POST /memory/ingest` — store conversation message.
- `POST /memory/search` — semantic search.
- `POST /memory/summarize-session` — summarize.
- `POST /api/v1/memory/notes` — write note (per ADR-0010).
- `DELETE /api/v1/memory/notes/{id}` — forget.
- `POST /api/v1/tools/memory/search` — tool surface.
- `GET /api/v1/audit/events` — audit query.

### 7.2 Proposed additions

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/memory/obsidian/index` | Index changed Markdown files (mtime + hash). |
| `POST` | `/api/v1/memory/obsidian/reindex` | Full reindex. Dangerous; gated by confirmation flow. |
| `POST` | `/api/v1/memory/search` | Already exists; extend with `rerank` + `filters` per § 7.3 |
| `POST` | `/api/v1/memory/write-proposal` | Stage a draft note; returns proposal id, no file write. |
| `POST` | `/api/v1/memory/write-confirmed` | Land the proposal into the vault. |

`write-confirmed` reuses the existing [`MemoryNoteService`](../../apps/memory-service/src/main/java/org/jarvis/memory/obsidian/MemoryNoteService.java) write path; the proposal table is new (out of scope for this doc draft).

### 7.3 Search request shape (canonical)

Request:

```json
{
  "query": "...",
  "topK": 30,
  "rerank": true,
  "filters": {
    "project": "Jarvis2.0",
    "tags": ["memory"],
    "noteType": "memory_fact"
  }
}
```

Response:

```json
{
  "results": [
    {
      "notePath": "07_Memory/Projects/jarvis-vault-design.md",
      "title": "Vault design — final layout",
      "headingPath": "Vault design > Templates",
      "score": 0.91,
      "rerankScore": 0.84,
      "excerpt": "...",
      "tags": ["jarvis/memory", "area/architecture"]
    }
  ],
  "auditId": "uuid-of-memory_search_audit-row"
}
```

`auditId` lets the desktop UI trace which retrieval produced which answer.

---

## 8. Model routing (PHASE 6)

### 8.1 Rules

1. **Deterministic intent** (e.g. `set timer`, `play music`) → handled in orchestrator with no LLM call.
2. **Vault question** (semantics: "what did I write about X") → embedding search → reranker → **Gemma**.
3. **Code/repo question** (file paths, language constructs, refactor) → repo grep + chunk → **Devstral**.
4. **Vision / screenshot / document image** → **Gemma** (only model in the stack with image input).
5. **Architecture / reasoning** → Gemma by default. If the prompt is dominated by code blocks (`>50%` non-prose), route to Devstral.
6. **No external cloud fallback** — if a local model is unavailable, the API must return `503` with reason `local-llm-unavailable`. **Never** silently call out.

The router itself is a small deterministic classifier (regex + heuristics + token-block ratio) before any LLM call.

### 8.2 Configuration block (proposed)

```yaml
jarvis:
  ai:
    models:
      router:
        type: deterministic   # alternative: small-llm
      main:
        id: gemma-3-27b-it-q4_k_m
        runtime: ollama       # or: llamacpp
        endpoint: http://127.0.0.1:18080
        contextLength: 32768  # cap below 128k for VRAM sanity
      coding:
        id: devstral-small-2507-q4_k_m
        runtime: ollama
        endpoint: http://127.0.0.1:18081
        contextLength: 32768
      embeddings:
        id: nomic-embed-text-v1.5
        runtime: ollama
        endpoint: http://127.0.0.1:11434
        dimensions: 768
        # OR keep multilingual-e5-small at 384; see § 6.3
      reranker:
        id: granite-embedding-reranker-english-r2
        runtime: tei
        endpoint: http://127.0.0.1:5002
  memory:
    obsidian:
      vaultPath: /home/kwaqa/Obsidian/JarvisVault
    externalNetworkAllowed: false
```

Wired as a `@ConfigurationProperties("jarvis.ai")` class living in `llm-service`. Defaults must keep current behavior (Qwen) until the swap is approved.

---

## 9. Runtime choice (PHASE 7)

| Runtime | GGUF | OpenAI-compatible API | Easy model switching | Java/Spring fit | VRAM management | Recommendation |
|---|---|---|---|---|---|---|
| **Ollama** | yes | yes (`/api/chat`, `/v1/chat/completions`) | excellent (lazy load, auto-unload) | great via REST | auto offload | **default** |
| **llama.cpp server** | yes (native) | yes (`server` binary) | manual (one process per model) | great via REST | manual `--n-gpu-layers` | **fallback** when raw control is needed |
| **LM Studio** | yes | yes | GUI-driven; not headless-friendly | OK for local dev | GUI controls | **dev convenience only**, not for service runtime |
| **vLLM** | no (PyTorch / HF) | yes | one model per process | great via REST | full GPU only | **no** for 12 GB VRAM Q4 GGUF — vLLM doesn't run GGUFs efficiently and prefers full-precision |

**Recommended default: Ollama**. It already supports both Gemma 3 27B (default Q4_K_M, 17 GB) and Devstral 24B (Q4_K_M-equivalent, 14 GB). Lazy load + auto-unload keeps headroom for the desktop. The existing [`LlmLifecycleManager`](../../apps/llm-service/src/main/java/org/jarvis/llm/service/LlmLifecycleManager.java) already speaks to per-port REST runtimes; pointing it at Ollama-served ports is a config change.

**Fallback: llama.cpp server**. Use it if:
- Ollama's bundled GGUF lags upstream (it sometimes does for new releases).
- You want to ship custom-quantized weights (e.g., `Q5_K_M` for Devstral with the 16.8 GB official Mistral GGUF).
- You need fine `--n-gpu-layers` control to share VRAM with a different process.

The reranker does **not** belong in Ollama. Run IBM Granite reranker via Hugging Face TEI in a separate `127.0.0.1:5002` container or a small sentence-transformers HTTP wrapper.

---

## 10. Privacy & security hardening (PHASE 8)

Concrete controls. Each is enforceable; each has a verification step.

| # | Control | How | Verification |
|---|---|---|---|
| 1 | Local-only model endpoints | Bind Ollama / llama.cpp / TEI / embedding-service-py to `127.0.0.1` only | `ss -tlnp \| grep 11434` shows `127.0.0.1` |
| 2 | No external embedding APIs | `jarvis.memory.embedding.url` must match `^http://127\.0\.0\.1` | Startup validator (already present pattern in `MemoryStartupValidator`) |
| 3 | No external reranker APIs | Same regex on rerank URL | Startup validator |
| 4 | Egress firewall | systemd unit `IPAddressDeny=any`/`IPAddressAllow=localhost` for `llm-service`, `memory-service`, `embedding-service-py`, reranker | `systemd-analyze security memory-service.service` |
| 5 | Obsidian Sync disabled | Vault has no `.obsidian/sync/` enabled config | One-shot script: `find $VAULT -name "sync-*.json"` returns empty |
| 6 | Community plugins untrusted | `.obsidian/community-plugins.json` empty by default; reviewed list checked into vault config repo | Boot script audit |
| 7 | No secrets in notes | Pre-commit hook in vault git repo running `gitleaks` or equivalent; memory-service writer rejects body containing `BEGIN PRIVATE KEY`, JWT-pattern, or `password=` outside fenced code | Unit test on `ObsidianVaultWriter` |
| 8 | Audit log on every retrieval | `memory_search_audit` row per `/memory/search` call (§ 6.4) | Integration test |
| 9 | Vault read-only by default | memory-service Vault path mounted with the JVM user only having write permission via the `MemoryNoteService`; manual edits done by user, not Jarvis | OS-level file mode 644, dir 755; user owns; service runs under same user but only writes through `ObsidianVaultWriter` |
| 10 | Backups encrypted | `restic` to a local USB or Borg with `--encryption=repokey-blake2`; `borg key export` stored offline | Weekly cron + restore drill |

---

## 11. Implementation slice (this PR)

**What this PR adds:** documentation only.

- This file: `docs/architecture/local-ai-memory-stack.md`
- [Vault templates](../obsidian-vault-templates/) — five Markdown templates and a README

**What this PR does NOT change:** any Java code, any application.yml, any Flyway migration, the runtime, the existing vault contents.

The reason is § 1: ground truth is the existing memory-service. Substantive code changes (model swap, dimension change, reranker, new migrations, ADR-0011) are decisions you should sign off on first. The drafts in this doc are sufficient for a follow-up PR.

---

## 12. Open questions — decisions needed before code lands

1. **Embeddings:** keep `multilingual-e5-small` (Russian-friendly) or switch to `nomic-embed-text-v1.5` (English-strong, longer context, accepts Russian-quality regression)? My recommendation: **keep e5-small for now**; revisit after a multilingual reranker plan is settled.
2. **Vault layout:** accept ADR-0011 (renames `03_Memory` → `07_Memory` etc.) or keep ADR-0010 strictly?
3. **Reranker scope:** add Granite reranker as a fourth local sidecar, or skip until retrieval quality data shows it pays?
4. **Model swap timing:** swap Qwen → Gemma + Devstral on the host daemon ports together (cleaner) or staged (safer rollback)?
5. **Egress control:** systemd sandbox on the services, or only application-level URL allowlists, or both?
6. **Vault write posture:** keep ADR-0010's direct-write semantics, or move all Jarvis-initiated writes through the proposal flow (§ 7)?

---

## 13. Risks / unknowns (brutally honest)

- The current LLM runtime is **Qwen** (Chinese), which violates your stated rule. Until swapped, the system is non-compliant with its own privacy/origin policy.
- **12 GB VRAM cannot fully host either Gemma 3 27B or Devstral 24B at Q4.** Performance with partial offload is acceptable for chat but is not benchmarked here.
- Granite reranker disk size and exact local-runtime ergonomics are **inferred, not measured**.
- The "no Russian model" rule is satisfied by the candidates here; the "Russian-language Jarvis" requirement is **only weakly satisfied** if you also drop multilingual-e5-small.
- ADR-0010 vs the requested vault layout collide; this is not a code bug but it is a real reorg cost.
- No file-watcher exists today — `/api/v1/memory/obsidian/index` is a hand-driven re-scan, not a true incremental indexer.

---

## 14. Next steps (P0 / P1 / P2)

**P0 (this week)**
- Land this design doc (PR1).
- Decide Open Questions § 12.
- Write ADR-0011 if vault rename is approved.

**P1 (next sprint)**
- Draft Flyway V6 (`memory_note_chunk`) and V7 (`memory_search_audit`) per § 6.4.
- Replace Qwen on host daemon ports with Gemma 3 (port 18080) and Devstral (port 18081). Update `AiRuntimeStatusService` canonical-stack string.
- Add `jarvis.ai.models.*` `@ConfigurationProperties` in llm-service; default to current Qwen behavior until cutover; flip via env var.
- Add `MemorySearchAuditService` writing `memory_search_audit` rows.

**P2 (after P1 ships)**
- File watcher path for `/api/v1/memory/obsidian/index`.
- Granite reranker sidecar at `:5002` + extension to `MemoryService.search`.
- Egress sandbox via systemd unit hardening.
- Gemma fallback to 12B for low-VRAM scenarios (verify size first).

---

## 15. References

- [ADR-0010 Obsidian as the human-readable memory layer](ADR/ADR-0010-obsidian-memory.md)
- [SPEC-1-Jarvis-Local-AI-Operating-System.md](SPEC-1-Jarvis-Local-AI-Operating-System.md)
- [memory-service](../../apps/memory-service/)
- [llm-service](../../apps/llm-service/)
- HuggingFace model cards (cited inline § 3.1)
- Ollama library pages (cited inline § 3.1)
