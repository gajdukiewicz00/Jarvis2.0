# Domain Gaps

This file separates what was fixed from what still requires larger redesign.

## Fixed In This Cleanup

- `user-profile` no longer keeps duplicate runtime goal/habit models in active code.
- `user-profile` no longer presents conversation-memory-like ownership as part of the profile domain runtime.
- `user-profile` now exposes a canonical `planning-context` contract for planner consumption.
- `planner-service` daily planning now consumes real profile context instead of relying on decorative profile integrations.
- `planner-service` daily and weekly planning now declare their generation mode and data sources more honestly.
- `planner-service` LLM controller paths now return `501 NOT_IMPLEMENTED` instead of fake smart behavior.
- Planner optional LLM adapter paths no longer silently pretend to enhance content.
- `life-tracker` finance and calendar writes now require delegated user context and ignore forged request-body ownership.
- `smart-home-service` no longer falls back to `local-user`; delegated user context is now required.
- Tests now cover profile-aware planning, honest planner LLM behavior, user-context enforcement, and profile contract aggregation.

## Remaining Gaps

### 1. Orchestrator fallback ownership

`orchestrator` still contains `local-user` fallback behavior on some internal smart-home and PC-control paths.

Why this remains:
- It sits outside the primary domain-service ownership cleanup.
- Fixing it cleanly needs coordinated integration changes, not only domain-service edits.

### 2. Planner depth is still limited

Planner is now honest, but it is still mostly:
- task distribution
- habit-aware daily structuring
- simple rule-based recommendations

What is still missing for a deeper planner:
- goal decomposition
- conflict resolution across multiple objectives
- real scheduling optimization with constraints

### 3. Analytics heuristics are shallow

Analytics uses real life-tracker data, but several insights are still based on simple grouping and keyword heuristics.

Why this matters:
- This is acceptable for a derived service.
- It should not be marketed as behavioral intelligence.

### 4. Smart-home state is runtime-local

`smart-home-service` has a clear execution contract, but its device catalog and state model are still static/in-memory.

What would be needed for completion:
- durable device inventory
- clear ownership of room/home topology
- persistent device registration lifecycle

### 5. PC-control scenarios are runtime-oriented

`pc-control` is a valid executor, but scenario definitions are still in-memory and local-runtime oriented.

What would be needed for completion:
- persisted per-user scenario ownership
- clearer policy boundaries for workstation capabilities

### 6. Legacy profile schema leftovers

Runtime ownership cleanup removed duplicate profile-domain code, but old schema objects related to memory/conversation still exist in migrations.

Why this remains:
- Removing them safely needs migration planning and data compatibility work.

## Separate Refactors, Not Cleanup Tweaks

The following should be treated as new refactoring tracks, not as follow-up polish:

- End-to-end delegated user-context propagation through orchestrator and all internal consumers
- Durable smart-home inventory and topology ownership
- Durable PC scenario ownership model
- Optional LLM capabilities moved behind explicit `llm-service` contracts instead of planner-local abstractions
- Schema cleanup migrations for removed legacy `user-profile` ownership concepts
