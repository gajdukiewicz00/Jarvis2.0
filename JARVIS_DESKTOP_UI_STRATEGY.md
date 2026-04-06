# Jarvis Desktop UI Strategy

## Decision

Jarvis should converge to **one desktop product shell**, not one backend service.

The right target is:

- one JavaFX desktop application
- one installer / runtime image
- one design system
- one navigation shell
- multiple feature modules inside that client

The backend service topology should stay as it is. The desktop work is a **product-surface consolidation**, not a service merge.

Confidence: **high**

## Why This Direction Is Correct

The current repo already has two real desktop-facing modules:

- `apps/desktop-client-javafx` is the user-facing JavaFX client with Home, Voice, Devices, PC Control, Life, Analytics, and Settings tabs.
- `apps/launcher-javafx` is the runtime and diagnostics JavaFX surface with backend lifecycle, health, diagnostics, logs, and startup controls.

Those are two halves of one desktop product, not two independent products.

If they are merged into one backend service, the result would blur four concerns that should stay separate:

- UI orchestration
- runtime management
- user-facing assistant UX
- infrastructure diagnostics

That would increase coupling and make the product harder to evolve. A unified desktop shell keeps those concerns together at the UX layer while preserving backend boundaries.

Confidence: **high**

## Current Reality

### Existing user-facing desktop surface

`apps/desktop-client-javafx` already contains:

- `DesktopApplication.kt` as the desktop entrypoint
- user tabs for `Home`, `Voice`, `Devices`, `PcControl`, `Life`, `Analytics`, and `Settings`
- voice session, WebSocket, auth, settings, and runtime-monitor plumbing

### Existing operational desktop surface

`apps/launcher-javafx` already contains:

- `LauncherApplication.kt` as the launcher entrypoint
- `ProcessRunner` for runtime lifecycle
- `HealthCheckService` for service health
- `DiagnosticsCollector` and `LogViewer` for diagnostics
- controls for optional LLM, memory, GPU, TLS repair, and acceptance actions

### Important architectural constraint

Both clients are Kotlin + JavaFX 21 today. The fastest safe path is to **unify product structure first and avoid a language rewrite during the same migration**.

Confidence: **high**

## Target Product Shape

Create a new desktop product module:

```text
apps/desktop-app-javafx
```

Inside it, use a modular shell:

```text
desktop-app-javafx/
  app-shell/
  design-system/
  feature-home/
  feature-assistant/
  feature-voice/
  feature-runtime/
  feature-diagnostics/
  feature-settings/
```

This does not require Maven submodules on day one. It can start as one Maven module with clear package boundaries and evolve later if needed.

Recommended package layout:

```text
org.jarvis.desktop.app
org.jarvis.desktop.shell
org.jarvis.desktop.design
org.jarvis.desktop.feature.home
org.jarvis.desktop.feature.assistant
org.jarvis.desktop.feature.voice
org.jarvis.desktop.feature.runtime
org.jarvis.desktop.feature.diagnostics
org.jarvis.desktop.feature.settings
```

Confidence: **high**

## Application Modes

The unified desktop app should support three top-level modes.

### 1. Boot / System mode

Purpose:

- launch runtime
- show startup progress
- expose service health
- surface diagnostics and recent logs
- test microphone, endpoints, TLS, and local model/runtime status

This is the spiritual successor of today's launcher UI.

### 2. Assistant mode

Purpose:

- provide the main Jarvis interaction surface
- center the experience around chat, voice, quick actions, and context
- keep runtime state visible but not dominant

This is the spiritual successor of today's desktop client.

### 3. Settings / Admin mode

Purpose:

- configure endpoints, voice, models, privacy, integrations, hotkeys, and notifications
- give operators and advanced users a clear control plane without mixing it into the assistant flow

Confidence: **high**

## Shell Architecture

### `app-shell`

Owns:

- stage and window lifecycle
- scene routing
- global search / command palette
- keyboard shortcuts
- responsive pane layout
- theme loading and token injection
- shared top bar, left nav, and optional right-side status/context pane

### `design-system`

Owns:

- color, spacing, typography, radius, elevation, and motion tokens
- reusable JavaFX controls
- common CSS and state styling
- card, list, form, status, and navigation primitives

### Feature modules

Each feature owns its screens, view models, UI state, and feature-specific services. It should not own global theme, navigation, or runtime bootstrap rules.

Confidence: **high**

## Navigation Model

### Desktop layout

Use a stable four-zone shell:

- `Top App Bar`
- `Navigation Drawer`
- `Main Content Pane`
- optional `Context / Status Pane`

This fits both assistant work and system diagnostics better than the current tab-only shell.

### Recommended left navigation

- Home
- Assistant
- Voice
- Planner
- Smart Home
- Analytics
- Devices
- Memory
- Security
- Settings
- Diagnostics

### Recommended top bar contents

- command/search field
- microphone state
- connection state
- local LLM state
- notifications
- profile / account menu

Confidence: **high**

## UX Rules

- Assistant-first by default; diagnostics should be reachable, not overwhelming.
- Every screen should have one clear primary action.
- Status should stay close to the entity it describes.
- Errors should explain both failure and recovery path.
- Long operations should show current phase, not only a spinner.
- Offline and degraded states should be first-class product states.
- Debug surfaces should stay clearly separated from end-user flows.

Confidence: **high**

## Visual Strategy

### Design language

Use **Material 3 principles for hierarchy, spacing, navigation, and accessibility**, then apply a Jarvis-specific premium-tech layer on top.

That means:

- dark theme only
- calm, cool palette
- strong readability
- spacious layout
- moderate rounding
- restrained motion
- minimal glass or blur effects
- glow only for meaningful active states

This should feel like a capable assistant console, not a neon sci-fi dashboard.

### Color tokens

| Token | Value |
| --- | --- |
| Background | `#0B0F14` |
| Surface | `#121821` |
| Surface Elevated | `#18212B` |
| Outline | `#2A3441` |
| Primary Accent | `#7CC7FF` |
| Secondary Accent | `#A78BFA` |
| Success | `#34D399` |
| Warning | `#FBBF24` |
| Error | `#F87171` |
| Text Primary | `#E6EDF3` |
| Text Secondary | `#9FB0C3` |

Rules:

- `Primary Accent` is for key actions and active navigation.
- `Secondary Accent` is for AI, memory, and smart features.
- red is never decorative
- glow appears only on active, listening, connected, or selected states

### Typography

Use a clean system sans-serif stack and keep density medium rather than compact.

Recommended scale:

- Display / page title
- Section title
- Card title
- Body
- Caption

Body text should land around the JavaFX equivalent of 14-15 px.

### Motion

- subtle fade and slide transitions
- restrained hover feedback
- fast state transitions
- no heavy blur, parallax, or showpiece animation

Confidence: **high**

## Design System Components

### Core primitives

- `JarvisButton`
- `JarvisIconButton`
- `JarvisCard`
- `JarvisSurface`
- `JarvisChip`
- `JarvisBadge`
- `JarvisToggle`
- `JarvisTextField`
- `JarvisSearchBar`
- `JarvisNavItem`
- `JarvisStatusPill`
- `JarvisSectionHeader`
- `JarvisEmptyState`
- `JarvisSkeleton`

### Feature-level components

- `AssistantMessageCard`
- `VoiceWavePanel`
- `ServiceHealthBoard`
- `QuickActionGrid`
- `TimelinePanel`
- `DeviceControlCard`
- `ModelRuntimeCard`
- `ActivityFeed`

These should be implemented as reusable JavaFX controls and CSS-backed styling primitives, not duplicated per feature.

Confidence: **high**

## Primary Screens

### Home

Contains:

- welcome / greeting
- quick actions
- system status
- recent events
- today view
- active devices

### Assistant

Contains:

- chat timeline
- input composer
- voice trigger / talk button
- tool/result cards
- pinned context

### Voice

Contains:

- microphone status
- wake word status
- STT/TTS availability
- live transcript
- session history

### Diagnostics

Contains:

- service health matrix
- latency and connection state
- WebSocket state
- device permissions
- local runtime and model state
- recent logs and events

### Settings

Contains:

- left category rail
- detailed form surface on the right
- `Apply`, `Reset`, and `Test` actions where appropriate

Confidence: **high**

## Mapping From Current Code To Target Features

| Current asset | Current role | Target home |
| --- | --- | --- |
| `DesktopApplication.kt` | desktop tab shell | `app-shell` bootstrap and route wiring |
| `HomeTab.kt` | basic home/runtime summary | `feature-home` |
| `VoiceTab.kt` | voice interaction surface | `feature-voice` |
| `SettingsTab.kt` | endpoint and client settings | `feature-settings` |
| `DevicesTab.kt` / `PcControlTab.kt` | device and desktop control UI | `feature-home` or dedicated feature packages |
| `LifeTab.kt` / `AnalyticsTab.kt` | domain surfaces | dedicated feature packages later |
| `DesktopRuntimeMonitor.kt` | runtime event/status model | shared shell/runtime support layer |
| `LauncherApplication.kt` | lifecycle + diagnostics UI | `feature-runtime` + `feature-diagnostics` |
| `ProcessRunner.kt` | backend lifecycle | `feature-runtime` support layer |
| `HealthCheckService.kt` | service health model | `feature-runtime` / `feature-diagnostics` |
| `DiagnosticsCollector.kt` / `LogViewer.kt` | diagnostics and logs | `feature-diagnostics` |

Confidence: **high**

## Migration Strategy

### Phase 0. Do not rewrite the backend

Keep:

- backend services
- runtime scripts
- service ownership boundaries
- voice and WebSocket contracts

The migration target is the desktop surface only.

### Phase 1. Create a shared design system

First extraction:

- palette and spacing tokens
- shared CSS variables/tokens
- buttons, cards, status pills, navigation items
- standard empty, loading, and error states

This gives both existing apps a common look before deeper consolidation.

### Phase 2. Introduce a shared app shell

Build:

- top bar
- left nav
- routed content area
- optional status/context pane

At this stage, existing screens can be wrapped instead of fully redesigned.

### Phase 3. Move launcher capabilities into runtime and diagnostics features

Bring over:

- health board
- process control
- logs
- diagnostics actions
- optional AI/runtime flags

Keep `launcher-javafx` as a temporary compatibility entrypoint during this phase.

### Phase 4. Move assistant-facing tabs into routed feature screens

Prioritize:

1. Home
2. Assistant
3. Voice
4. Diagnostics
5. Settings

This is the minimum product contour for a unified desktop release.

### Phase 5. Stabilize packaging and remove the old launcher UI

After the unified shell is stable:

- ship one runtime image / installer
- keep compatibility launcher scripts only as thin wrappers if still needed
- remove the separate launcher UI when parity is proven

Confidence: **high**

## MVP Scope

The first good unified release should include:

1. one desktop shell
2. left navigation and top bar
3. home screen
4. assistant screen
5. diagnostics screen
6. settings screen
7. one shared dark theme

Nice-to-have items such as advanced device cards, memory views, planner deep dives, or animated voice visualization should not block the MVP.

Confidence: **high**

## Explicit Non-Goals

- do not turn the UI into neon sci-fi overload
- do not mix debug UI into the primary assistant flow without boundaries
- do not add many accent colors
- do not build the product around modal popups
- do not move backend runtime logic into the presentation layer
- do not combine this consolidation with a Kotlin-to-Java rewrite

Confidence: **high**

## Implementation Notes

- Keep JavaFX and Kotlin for the first consolidation pass because both current desktop apps already use that stack.
- Prefer a token-driven CSS approach so theme and component styling stay centralized.
- Use adapter layers around current `Tab`-based content where possible to reduce migration risk.
- Treat launcher controls as feature panels inside the new shell, not as a separate top-level application identity.
- Preserve operational clarity: runtime controls should stay explicit and visible, but they should not dominate the assistant landing experience.

Confidence: **high**

## Recommended End State

Jarvis should present itself on desktop as one cohesive product:

- a calm dark assistant interface for daily use
- a visible but contained system/runtime layer
- a clear settings/admin plane
- a single interaction model and design language across all surfaces

The clean recommendation is:

**Unify `desktop-client-javafx` and `launcher-javafx` into one JavaFX desktop product shell, while keeping backend services separate and modular.**

Confidence: **high**
