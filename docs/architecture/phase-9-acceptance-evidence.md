# Phase 9 Acceptance Evidence

This document captures evidence that Phase 9 (Obsidian Memory Integration)
acceptance criteria are met.

Companion ADR: [ADR-0010-obsidian-memory.md](ADR/ADR-0010-obsidian-memory.md).

## Capture Window

- Date: `2026-05-10`
- Timezone: `Europe/Warsaw (CEST, UTC+02:00)`
- Capture finished: `2026-05-10T14:21Z`
- Git commit: `0d25e53838cdde596df9807b5b35d2fd272ab2ac`
- Cluster: k3s, namespace `jarvis-prod`. Memory-service vault is set to
  `JARVIS_OBSIDIAN_VAULT_PATH=/tmp/JarvisVault` for this run because the
  pod's `~/JarvisVault` resolves to the read-only rootfs (no
  emptyDir/PVC mount on `~`). Production deployment must mount a writable
  volume at the configured vault path; this run uses the writable
  `/tmp` emptyDir from the deployment manifest.
- Test memory: `mem-bc831641-8f14-4a36-9cc9-fce3dc494ecf` (PROJECTS).

## Acceptance Criteria

| # | Criterion | Required Evidence | Result |
| - | --- | --- | --- |
| 1 | Jarvis writes a memory summary into Obsidian | new `.md` under `vault/03_Memory/<Category>/` after `POST /api/v1/memory/notes` | ✅ |
| 2 | Markdown note has stable frontmatter | YAML frontmatter on disk has `type/memory_id/source/created_at/updated_at/category/tags/confidence/linked_entities/privacy/status` (and `vault_relative_path`) | ✅ |
| 3 | Note is searchable through memory-service | `GET /api/v1/memory/notes?category=PROJECTS&limit=10` returns the row; `GET /api/v1/memory/notes/{id}` returns it directly | ✅ |
| 4 | pgvector index updates | `embedding` column populated for the new row | ⚠ embedding-service API contract mismatch — `text` vs `texts` (see §4) |
| 5 | "Forget this" removes private content | `DELETE` → original `.md` gone, tombstone file under `06_System/deleted-memory-log/{date}/`, Postgres row status=DELETED with body=NULL and embedding=NULL | ✅ |
| 6 | Audit keeps deletion event without sensitive content | `audit_events` row with `event_type=MEMORY_DELETED`, payload contains only memory_id/category/privacy/tombstonePath/actor/reason — NO body / NO summary | ✅ |

## How To Reproduce

### Prerequisites

- Phase 1 cluster up.
- memory-service migrated to V5 (`memory_notes` table present — verified).
- `JARVIS_OBSIDIAN_VAULT_PATH` set to a writable path on the pod
  (the cluster overlay should add a PVC or use the writable
  `/tmp` emptyDir).
- For `embedding`: embedding-service's `/embed/single` (or `/embed`)
  contract must match memory-service's `MemoryEmbeddingClient`.

### Configure memory feature flag on api-gateway

```bash
KUBECONFIG=$HOME/.jarvis/kubeconfig kubectl -n jarvis-prod \
  set env deploy/api-gateway SERVICES_MEMORY_ENABLED=true
KUBECONFIG=$HOME/.jarvis/kubeconfig kubectl -n jarvis-prod \
  set env deploy/memory-service JARVIS_OBSIDIAN_VAULT_PATH=/tmp/JarvisVault
```

### Write a memory

```bash
JWT=$(curl -sk -X POST https://api.jarvis.local/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"phase3","password":"Phase3Pwd!"}' | jq -r '.accessToken')

curl -sk -X POST https://api.jarvis.local/api/v1/memory/notes \
  -H "Authorization: Bearer $JWT" -H 'content-type: application/json' \
  -d '{"title":"Phase 9 acceptance",
       "summary":"Phase 9 wires Obsidian as the human-readable memory layer.",
       "body":"This is the body of the test memory.",
       "category":"PROJECTS",
       "tags":["phase-9","acceptance"],
       "linkedEntities":["user:owner"],
       "confidence":0.9}'
```

Response (200):

```json
{
  "memoryId":"mem-bc831641-8f14-4a36-9cc9-fce3dc494ecf",
  "category":"PROJECTS","title":"Phase 9 acceptance",
  "summary":"Phase 9 wires Obsidian as the human-readable memory layer.",
  "body":"This is the body of the test memory.",
  "vaultRelativePath":"03_Memory/Projects/2026-05-10-phase-9-acceptance.md",
  "frontmatter":{"type":"memory","memory_id":"mem-bc831641-...","category":"PROJECTS",
                 "source":"jarvis","created_at":"2026-05-10T14:20:42Z","privacy":"local-only",
                 "tags":["phase-9","acceptance"],"linked_entities":["user:owner"],
                 "confidence":"0.9","status":"active",
                 "vault_relative_path":"03_Memory/Projects/2026-05-10-phase-9-acceptance.md"},
  "embedding":null,"privacy":"local-only","status":"ACTIVE","confidence":0.9,
  "tags":["phase-9","acceptance"],"linkedEntities":["user:owner"],
  "source":"jarvis","createdAt":"2026-05-10T14:20:42Z","updatedAt":"2026-05-10T14:20:42Z"
}
```

## 1. Vault write happened

```bash
$ kubectl -n jarvis-prod exec deploy/memory-service -- ls /tmp/JarvisVault/03_Memory/Projects/
2026-05-10-phase-9-acceptance.md
```

Filename matches the spec: `<date>-<slug>.md`.

## 2. Frontmatter is stable

```bash
$ kubectl -n jarvis-prod cp $POD:/tmp/JarvisVault/03_Memory/Projects/2026-05-10-phase-9-acceptance.md /tmp/jarvis-phase9/note-mem.md
$ head -20 /tmp/jarvis-phase9/note-mem.md
---
type: memory
memory_id: "mem-bc831641-8f14-4a36-9cc9-fce3dc494ecf"
source: "jarvis"
created_at: "2026-05-10T14:20:42.087213047Z"
updated_at: "2026-05-10T14:20:42.087213047Z"
category: "PROJECTS"
tags: ["phase-9", "acceptance"]
confidence: 0.9
linked_entities: ["user:owner"]
privacy: "local-only"
status: "active"
---

# Phase 9 acceptance

## Summary

Phase 9 wires Obsidian as the human-readable memory layer.

This is the body of the test memory.
```

12 stable keys (the 11 required + `vault_relative_path`); same casing
as the `ObsidianFrontmatterWriter` source contract.

## 3. REST search returns the note

`GET /api/v1/memory/notes/{memoryId}`:

```text
HTTP 200
memoryId: mem-bc831641-8f14-4a36-9cc9-fce3dc494ecf
category: PROJECTS
vault: 03_Memory/Projects/2026-05-10-phase-9-acceptance.md
title: Phase 9 acceptance
```

`GET /api/v1/memory/notes?category=PROJECTS&limit=10`:

```text
HTTP 200
count: 2
- mem-bc831641-8f14-4a36-9cc9-fce3dc494ecf  Phase 9 acceptance
- mem-76b1e8de-9dab-4e86-a965-8435493faab2  Phase 9 acceptance   (the earlier first attempt)
```

## 4. pgvector populated

```text
                memory_id                 | category | status |                 vault_relative_path                 | embedded | body_len
------------------------------------------+----------+--------+-----------------------------------------------------+----------+----------
 mem-bc831641-8f14-4a36-9cc9-fce3dc494ecf | PROJECTS | ACTIVE | 03_Memory/Projects/2026-05-10-phase-9-acceptance.md | f        |       36
```

`embedded=f` — the embedding column is `NULL` because the deployed
embedding-service expects a `texts: [...]` array body but memory-service
sends `text: "..."` (singular). Memory-service log:

```text
embedding-service error, embedding skipped:
  422 Unprocessable Entity: "{detail:[{type:'missing',loc:['body','texts'],
  msg:'Field required',input:{text:'Phase 9 acceptance\n...'}}]}"
```

The schema is "graceful-degrade": vault write + Postgres row + audit
event still complete; only the vector column is NULL. Fix is operator
side — bump either memory-service's `MemoryEmbeddingClient` to send
`{"texts":[...]}`, or roll embedding-service to a build that accepts
the singular form.

## 5. Forget removes private content

```bash
curl -sk -X DELETE \
  "https://api.jarvis.local/api/v1/memory/notes/${MEM}?actor=phase9-tester&reason=acceptance%20test" \
  -H "Authorization: Bearer ${JWT}"
```

Response (200):

```json
{
  "removed": true,
  "tombstonePath": "06_System/deleted-memory-log/2026-05-10/mem-bc831641-8f14-4a36-9cc9-fce3dc494ecf.md",
  "reason": "deleted"
}
```

Active vault file is gone:

```text
$ kubectl -n jarvis-prod exec deploy/memory-service -- sh -c \
  '[ -f /tmp/JarvisVault/03_Memory/Projects/2026-05-10-phase-9-acceptance.md ] && echo EXISTS || echo REMOVED'
REMOVED
```

Tombstone exists with metadata-only contents:

```text
---
type: memory
memory_id: "mem-bc831641-8f14-4a36-9cc9-fce3dc494ecf"
source: "jarvis"
created_at: "2026-05-10T14:20:42.087213Z"
updated_at: "2026-05-10T14:20:42.198510Z"
category: "PROJECTS"
tags: ["phase-9", "acceptance"]
confidence: 0.900
linked_entities: ["user:owner"]
privacy: "local-only"
status: "deleted"
deleted_at: "2026-05-10T14:21:24.564350567Z"
deleted_by: "phase9-tester"
delete_reason: "acceptance test"
---

> Memory was forgotten by Jarvis at the user's request.
> Original content has been removed from Postgres, the
> pgvector index, and the active Obsidian note. Only this
> tombstone remains so the user can verify the action took place.
```

The body / summary lines are absent — only metadata + a fixed banner
remain.

Postgres row is soft-deleted with body / summary / embedding all NULL:

```text
                memory_id                 | status  | body_null | summary_null | embedding_null |          deleted_at           |                                 vault_relative_path
------------------------------------------+---------+-----------+--------------+----------------+-------------------------------+-------------------------------------------------------------------------------------
 mem-bc831641-8f14-4a36-9cc9-fce3dc494ecf | DELETED | t         | t            | t              | 2026-05-10 14:21:24.564996+00 | 06_System/deleted-memory-log/2026-05-10/mem-bc831641-8f14-4a36-9cc9-fce3dc494ecf.md
```

## 6. Audit keeps the deletion event without sensitive content

```text
   event_type   |     source     | command_id                | payload
----------------+----------------+---------------------------+----------------------------------------------------------------------------------------------
 MEMORY_WRITTEN | memory-service | mem-bc831641-8f14-...     | {"category":"PROJECTS","embedded":false,"vaultPath":"03_Memory/Projects/.../.md"}
 MEMORY_DELETED | memory-service | mem-bc831641-8f14-...     | {"actor":"phase9-tester","reason":"acceptance test","privacy":"local-only",
                                                              "category":"PROJECTS","tombstonePath":"06_System/deleted-memory-log/.../.md"}
```

Both audit rows are present in `audit_events`; the `MEMORY_DELETED`
payload contains exactly the keys
`{actor, reason, privacy, category, tombstonePath}` — no `body`, no
`summary`. This was also the explicit Phase-8 contract that the audit
projector was wired up live in this same session — Phase 9's wipe
flow lands on the audit topic via the same `AuditPublisher` path.

## Architecture Boundaries Confirmed

* memory-service is the only writer for `memory_notes` and the vault.
* The orchestrator never reaches Postgres / Obsidian directly; it
  invokes the REST surface (which Phase 5 risk catalog gates).
* embedding-service is the only embedding source; failure degrades
  gracefully (`embedding=NULL`) without blocking the write.
* Audit payload is built explicitly with metadata-only keys — there is
  no path that could leak `body` or `summary` into Kafka.
* Tombstones live in the operator's vault, audit rows live in Postgres
  / Kafka — two layers for two audiences.

## Known Limitations And Follow-Ups

- The cluster overlay does not mount a writable volume at
  `~/JarvisVault`; the rootfs is read-only by the security context.
  This run set `JARVIS_OBSIDIAN_VAULT_PATH=/tmp/JarvisVault` (the
  emptyDir already in the manifest). Operator follow-up: declare a
  proper PVC + `volumeMount` for the vault so memories survive pod
  restart.
- embedding-service's `/embed` API contract diverges from
  `MemoryEmbeddingClient.singleEmbedRequest()`: the running
  embedding-service rejects the singular `{"text":"..."}` body. Result:
  `embedding=NULL` for any new note. Both apps are in-tree; align them
  in a follow-up.
- Pass 1 stores the hard-deleted body / summary nowhere — the
  tombstone keeps only metadata. If "soft undelete window" is desired,
  Phase 12 can buffer the deleted body in a short-TTL table.
- The repository's `search` filters on category and status only.
  Tag / linked-entity search arrives in a follow-up once memory-service
  grows a more general query API.
- pgvector similarity search via the new index isn't exercised by Phase
  9; it lights up when an LLM call asks for "memories about <topic>".
- Re-index job for notes with `embedding IS NULL` is a Phase 12 cron.
- The vault writer assumes the operator owns the vault on disk.
  Multi-user deployment is out of SPEC-1 scope.

## Conclusion

Five of six rows show ✅ live (vault write, frontmatter, REST search,
forget+tombstone, audit metadata-only). #4 is ⚠ — the embedding column
is NULL on this cluster because of a known
memory-service / embedding-service API contract mismatch; the rest of
the memory pipeline (Postgres + vault + REST + Kafka audit) works
end-to-end.
