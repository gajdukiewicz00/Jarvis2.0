# ADR-0010: Obsidian as the human-readable memory layer

## Status

Accepted (Phase 9).

## Context

Phases 0-8 made memory machine-queryable: PostgreSQL holds chunks,
pgvector serves semantic search, the Phase 8 audit projector lands
every privileged action in `audit_events`. None of that is **readable
by the user**, and SPEC-1 § "Obsidian Vault Role" mandates Obsidian as
a first-class storage target — not a notes app afterthought.

The "Jarvis, forget this" command is also part of the SPEC: deleting a
memory must wipe Postgres, pgvector, AND the corresponding Markdown
file in one consistent operation, while leaving an audit row that does
NOT carry the sensitive content itself.

## Decision

We add an `obsidian/` package inside `memory-service` (per SPEC-1 §
"Add New Modules": "obsidian-sync — submodule of memory-service").

### Storage layers

```
caller (orchestrator / REST)
   │
   ▼
MemoryNoteService.write(request)
   ├── 1. PostgreSQL (memory_notes row, status=ACTIVE)        ← source of truth
   ├── 2. ObsidianVaultWriter (Markdown file at vault path)   ← human readable
   ├── 3. MemoryEmbeddingClient → embedding-service (vector)  ← pgvector index
   └── 4. AuditPublisher.audit(MEMORY_WRITTEN)                ← Phase 8 trail
```

The order is intentional: Postgres commits first so an Obsidian /
embedding outage cannot leave Jarvis without a record of the user's
intent. The vault write and embedding compute happen after the row is
saved and update the same row in-place.

### Vault layout

Mirrors SPEC-1 exactly:

```
JarvisVault/
  03_Memory/{Preferences,Habits,Projects,Health,Finance,Time,People}/
  04_Reports/
  05_Decisions/
  06_System/deleted-memory-log/{YYYY-MM-DD}/{memory_id}.md   ← tombstones
```

`MemoryCategory` enum maps each value to its directory; the writer
creates parent directories on demand and writes files atomically
(temp + ATOMIC_MOVE → REPLACE_EXISTING).

### Frontmatter

`ObsidianMarkdownRenderer` produces YAML with a fixed key order so
diffs across re-renders are clean:

```
---
type: "memory"
memory_id: "mem-..."
source: "jarvis"
created_at: "2026-05-01T10:00:00Z"
updated_at: "2026-05-01T10:00:00Z"
category: "PROJECTS"
tags: ["jarvis/memory", "phase-9"]
confidence: 0.87
linked_entities: ["user:owner", "project:jarvis"]
privacy: "local-only"
status: "active"
---
# Title
## Summary
...
body...
```

### Forget flow

`MemoryForgetService.forget(memoryId, actor, reason)` runs a
deterministic three-layer wipe:

1. **Obsidian** — write a tombstone to
   `06_System/deleted-memory-log/{YYYY-MM-DD}/{memory_id}.md` carrying
   only metadata (no body, no summary), then remove the original
   active note. Tombstone failures are logged but do **not** block the
   Postgres / pgvector wipe — the operator's intent is clear.
2. **pgvector** — null the `embedding` column.
3. **Postgres** — soft-delete: status=DELETED, body cleared, summary
   cleared, vault_relative_path repointed at the tombstone, deleted_at
   set. The row stays so audit foreign keys (`command_id` → memory) keep
   working; a Phase 12 retention job can hard-delete after N days.
4. **Audit** — emit `MEMORY_DELETED` carrying only `memoryId`,
   `category`, `privacy`, `tombstonePath`, `actor`, `reason`. **No
   summary or body** travels into the audit topic, so even an
   eyes-on-Kafka analyst cannot recover the deleted content.

Idempotent: forgetting an already-DELETED row returns
`reason=already-deleted` without re-tombstoning.

## Consequences

* `memory-service` grows two REST surfaces:
  `POST /api/v1/memory/notes` (write) and
  `DELETE /api/v1/memory/notes/{id}` (forget). Both inherit the
  module's existing JWT enforcement at the gateway edge.
* The orchestrator's risk catalog already classifies
  `memory.delete-bulk` as HIGH/`BULK_MEMORY_MODIFY` and
  `memory.delete-entry` as MEDIUM. Phase 5's confirmation flow gates
  the call before this service is touched.
* The Postgres row persists post-delete — by design — so the audit
  table can join to `memory_notes` for the deletion record long after
  the body is gone.
* Embedding failure is **not** fatal: the note still exists, the
  vault still has the Markdown, and a re-index job can backfill.
  This matches Phase 3's broader "missing optional capability degrades
  gracefully" pattern.
* Disabling the vault writer (`jarvis.memory.obsidian.enabled=false`)
  gives a clean way to run Jarvis on a non-owner host (CI, demo VM)
  without a populated vault. Postgres + pgvector keep working.

## Alternatives considered

* **Separate `obsidian-sync` Maven module.** SPEC-1 explicitly allows
  starting it as a memory-service submodule. The package
  (`org.jarvis.memory.obsidian.*`) is self-contained — extraction to
  a new module is mechanical when memory-service's responsibilities
  outgrow a single deployable.
* **Hard delete on forget.** Rejected. Audit foreign keys + timeline
  reconstruction need the row to exist. The body is what's
  sensitive; the row is just a marker.
* **Tombstone-only (no body in any layer).** Rejected. The tombstone is
  in the operator's vault and only contains metadata; it is the local
  proof-of-deletion. Audit lives in Kafka / Postgres and is even more
  redacted. Two layers for two audiences (the user vs. the analyst).
* **Embedding inside this service.** Rejected. embedding-service is the
  dedicated boundary (Phase 3); duplicating the model in Java would
  bloat the runtime and break the SPEC-1 isolation rule.

## References

* SPEC-1 § "Obsidian Vault Role"
* SPEC-1 § Phase 9 task list
* SPEC-1 § "Add New Modules" (obsidian-sync)
* `apps/memory-service/.../obsidian/`
* `apps/memory-service/src/main/resources/db/migration/V5__add_memory_notes.sql`
* [phase-9-acceptance-evidence.md](../phase-9-acceptance-evidence.md)
