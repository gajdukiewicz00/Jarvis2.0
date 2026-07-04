# Obsidian Vault Templates

Reference templates for the JarvisVault, used by both the human owner and the
Jarvis memory pipeline. They define the YAML frontmatter contract that
[ObsidianMarkdownRenderer](../../apps/memory-service/src/main/java/org/jarvis/memory/obsidian/ObsidianMarkdownRenderer.java) will produce and that any
indexer / scanner can rely on.

See [docs/architecture/local-ai-memory-stack.md § 5](../architecture/local-ai-memory-stack.md#5-obsidian-vault-layout-phase-3) for layout
context. The templates here are **drafts** — they are not yet wired into a
file-watcher or template picker. They live in `docs/` (not in the vault
itself) so they ship with the repo.

## Frontmatter contract (canonical order)

| Field | Type | Required | Notes |
|---|---|---|---|
| `type` | enum | yes | `decision` / `project` / `memory_fact` / `daily` / `source` |
| `created` | ISO-8601 UTC | yes | immutable after first write |
| `updated` | ISO-8601 UTC | yes | bump on every edit |
| `tags` | list[str] | yes | namespaced (e.g. `area/finance`, `jarvis/memory`) |
| `source` | str | yes | `jarvis`, `manual`, `clipped:<url>` |
| `confidence` | float 0..1 | required for `memory_fact` and `source`; optional otherwise | <0.6 → stays in `00_Inbox/` |
| `project` | str | optional | matches a `02_Projects/<name>` folder |
| `privacy` | enum | yes | `local-only` (default) or `share-team` (future) |
| `status` | enum | optional | `active` / `archived` / `superseded` |

## Files

- [decision.md](decision.md) — architecture / process decision (ADR-style, lighter)
- [project.md](project.md) — project overview, goals, links
- [memory-fact.md](memory-fact.md) — atomic fact Jarvis is allowed to recall
- [daily.md](daily.md) — daily log
- [source.md](source.md) — clipped external reference

## Conventions

- File slug = lowercase, hyphenated, no spaces. The writer enforces this.
- `created` and `updated` use UTC with `Z` suffix.
- `tags` use forward-slash namespaces; do not use `#tag` syntax in
  frontmatter, only in body.
- Keep frontmatter under 30 lines — anything longer should live in the body.
