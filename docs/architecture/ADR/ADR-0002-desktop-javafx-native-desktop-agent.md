# ADR-0002: desktop-javafx Is The Native Desktop Agent

- Status: Accepted
- Date: `2026-04-28`

## Decision

`desktop-javafx` is the current official Native Desktop Agent implementation.

## Context

The repo contains a real desktop JavaFX module with launcher, shell, diagnostics, runtime awareness, WebSocket clients, and local desktop integration responsibilities.

It is the current implementation that connects Jarvis to:

- local UI
- local auth bootstrap
- local microphone and wake-word flows
- workstation-local websocket sessions
- native device and desktop context

## What this means in Phase 0

- `desktop-javafx` is not deprecated.
- `desktop-javafx` is not treated as a legacy side path.
- documentation must describe it as the current native desktop implementation
- no module rename is required in this phase

## Relationship To Launcher Modules And Scripts

- launcher helpers such as `scripts/product/jarvis-launcher.sh` support the same desktop runtime path
- the desktop shell and launcher responsibilities currently coexist in the same module and runtime family
- if future refactoring splits or renames those surfaces, that work belongs to a later phase

## Consequences

- Desktop/native responsibilities remain anchored in `desktop-javafx`.
- Voice capture and wake-word responsibility stay documented as native-host concerns.
- Service and architecture docs should refer to the Native Desktop Agent using the current module name instead of treating it as already replaced.
