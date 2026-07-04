# ADR-0003: Docker Runtime Deprecation Before Removal

- Status: Accepted
- Date: `2026-04-28`

## Decision

Docker runtime paths are deprecated before deletion.

## Context

The repo already contains:

- many service Dockerfiles
- Python worker Dockerfiles
- Docker-oriented helper docs
- local helper flows that still rely on containerized infrastructure, especially local PostgreSQL

At the same time, the target production architecture is native host + MicroK8s, not Docker Compose as the source of truth.

## Decision Details

- Dockerfiles, Docker-related build assets, and docker-specific compatibility scripts are kept during Phase 0.
- They are documented as deprecated runtime paths, not as the official production runtime.
- Existing Docker assets may still be modified for compatibility, evidence capture, or migration support.
- Newly added Docker runtime files are blocked by repository guardrails.
- Actual removal is deferred until MicroK8s and native-host parity is proven with evidence.

## Consequences

- Phase 0 stays low-risk and non-destructive.
- Working local/runtime paths are preserved.
- The repo stops drifting toward new Docker runtime sprawl while still preserving migration evidence.
- Future cleanup can remove Docker runtime paths from a stronger evidence base.

## Alternatives Considered

### Immediate deletion

Rejected because it would risk breaking working local/runtime paths before parity is proven.

### Continue treating Docker as a co-equal production runtime

Rejected because it conflicts with the target architecture and keeps multiple competing sources of truth alive.
