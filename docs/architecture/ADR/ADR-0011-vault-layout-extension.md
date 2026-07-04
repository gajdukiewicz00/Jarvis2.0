# ADR-0011: Vault layout — owner-curated namespaces alongside system memory

- Status: Proposed
- Date: 2026-05-09
- Supersedes: the **"Vault layout"** section of [ADR-0010](ADR-0010-obsidian-memory.md). All other parts of ADR-0010 (write order, frontmatter contract, forget flow) remain in force.

## Status note

This ADR is **proposed**, not yet accepted. It does not change file paths
under `MemoryCategory` or modify `application.yml` until accepted and
followed by a code-level migration PR. The doc-only changes that
accompany it (templates, design doc) are unaffected.

## Context

ADR-0010 fixed the vault layout as:

```
JarvisVault/
  03_Memory/{Preferences,Habits,Projects,Health,Finance,Time,People}/
  04_Reports/
  05_Decisions/
  06_System/deleted-memory-log/...
```

Two things have happened since:

1. The runtime config grew `00_Inbox/` and `01_Daily/` (see
   [memory-service application.yml lines 9-14](../../../apps/memory-service/src/main/resources/application.yml#L9-L14)).
   These were added without an ADR pass. ADR-0010 is partially out of
   date with the deployed reality.
2. The owner has chosen to use the vault as a primary working space
   ([docs/architecture/local-ai-memory-stack.md](../local-ai-memory-stack.md)),
   not only as a Jarvis-managed memory store. That introduces user-level
   namespaces — projects, knowledge, personal — that ADR-0010 didn't
   plan for. Concretely, the new design proposes
   `02_Projects/`, `03_Knowledge/`, `04_Personal/`, `06_Sources/`, `99_Archive/`
   on the same prefix scheme.

Without renumbering, the user's `03_Knowledge/` collides with ADR-0010's
`03_Memory/`, and `06_Sources/` collides with `06_System/`. Two folders
on the same numeric prefix defeats the prefix's purpose as a navigation
hint.

## Decision

The vault layout is renumbered so owner-curated and system-curated
namespaces have distinct prefixes. System-written categories are
**pushed up** to higher numbers; user-curated namespaces take the lower
prefixes since the owner navigates them more often.

```
JarvisVault/
  00_Inbox/                         ← unconfirmed Jarvis proposals (existed)
  01_Daily/                         ← daily journal (existed)
  02_Projects/                      ← user project notes (NEW)
  03_Knowledge/                     ← curated reference material (NEW)
  04_Personal/                      ← goals, finance, health, etc. (NEW)
  05_Decisions/                     ← unchanged from ADR-0010
  06_Sources/                       ← clipped/cited external sources (NEW)
  07_Memory/                        ← was 03_Memory in ADR-0010 (RENAMED)
    Preferences/
    Habits/
    Projects/
    Health/
    Finance/
    Time/
    People/
  08_Reports/                       ← was 04_Reports in ADR-0010 (RENAMED)
  99_Archive/                       ← cold storage (NEW)
  99_System/                        ← was 06_System in ADR-0010 (RENAMED)
    deleted-memory-log/{YYYY-MM-DD}/{memory_id}.md
```

Frontmatter contract from ADR-0010 is unchanged. The forget flow is
unchanged. The three-layer write order
(Postgres → Obsidian → pgvector → audit) is unchanged.

## Consequences

### Code changes required when this ADR is implemented

- `org.jarvis.memory.obsidian.MemoryCategory` — update each enum value's
  directory mapping from `03_Memory/<Cat>` to `07_Memory/<Cat>`. Keep
  enum names stable to avoid breaking serialized payloads.
- `application.yml`:
  - `jarvis.memory.obsidian.deleted-log-subdir`:
    `06_System/deleted-memory-log` → `99_System/deleted-memory-log`.
  - No changes to `inbox-subdir` (`00_Inbox`) or `daily-subdir`
    (`01_Daily`).
- One additive Flyway migration to rewrite
  `memory_notes.vault_relative_path` for existing rows whose path begins
  with `03_Memory/`, `04_Reports/`, or `06_System/`. Use a
  string-replace at the prefix only.
- A one-shot script (or admin endpoint) to physically rename existing
  vault directories. Run before the Flyway migration so the rewritten
  paths land on real files. The script lives outside the JVM service —
  out of scope for this ADR.

### Backward / forward compatibility

- Old desktop builds reading the vault directly will lose access to
  `03_Memory/...` files until they pick up the new layout. Coordinate
  the rollout: rename script first, then memory-service deploy with the
  new `MemoryCategory` mapping, then desktop client.
- Audit rows in `audit_events` referencing the old paths are not
  rewritten. They are historical evidence and should remain literally
  what was true at the time of write.

### Risks

- **Path migration is invasive**. Mitigation: do the rename in a single
  maintenance window; keep a `git tag` on the vault repo (if you keep
  the vault under git) so a revert is one command.
- **Tooling that hard-codes `03_Memory/`** (custom Obsidian plugins,
  shell scripts, dashboards) will break. Grep before the rename:
  `grep -r '03_Memory\|04_Reports\|06_System' .`
- **Pre-existing `00_Inbox/` / `01_Daily/` are not in ADR-0010**.
  They've been in production via `application.yml` defaults — this
  ADR formalizes them so the gap is closed.

## Alternatives considered

- **Keep ADR-0010, add new namespaces with collision-free prefixes
  (e.g. `30_Knowledge/`, `40_Personal/`).** Rejected. The current design
  doc and all user-facing templates already assume the simple `02..06`
  numbering. Picking arbitrary new prefixes complicates the user's
  navigation.
- **Drop the numeric prefix entirely.** Rejected. Numeric prefixes give
  Obsidian's file pane a stable sort order — which the owner relies on
  for daily navigation (`00_Inbox` always at top).
- **Move all system-written content under `99_System/Memory/`,
  `99_System/Reports/`** and surface only owner namespaces at the top.
  Rejected for now: `07_Memory/` is content the owner *does* read
  during reviews; hiding it under `99_System/` would inflate that
  folder beyond ops-only material.

## Implementation gate

This ADR moves to **Accepted** when:

1. The owner confirms the renumbering.
2. A code PR lands with: `MemoryCategory` mapping update, Flyway
   migration for path rewrite, application.yml updates, vault rename
   script in `scripts/` with a dry-run mode.
3. The migration script has been exercised in a non-prod copy of the
   vault.

Until then, all references to the new layout in
[docs/architecture/local-ai-memory-stack.md](../local-ai-memory-stack.md)
should be read as "after ADR-0011 is accepted".

## References

- [ADR-0010 Obsidian as the human-readable memory layer](ADR-0010-obsidian-memory.md)
- [docs/architecture/local-ai-memory-stack.md § 5](../local-ai-memory-stack.md#5-obsidian-vault-layout-phase-3)
- [SPEC-1-Jarvis-Local-AI-Operating-System.md](../SPEC-1-Jarvis-Local-AI-Operating-System.md)
- [memory-service application.yml](../../../apps/memory-service/src/main/resources/application.yml)
