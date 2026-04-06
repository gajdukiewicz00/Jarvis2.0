# Jarvis Desktop UI Blueprint

## Purpose

This document translates [JARVIS_DESKTOP_UI_STRATEGY.md](JARVIS_DESKTOP_UI_STRATEGY.md) into an implementation-grade blueprint for a unified Jarvis desktop application.

This blueprint is based on current repo reality, not on a greenfield product idea.

Primary evidence:

- root `pom.xml`
- `apps/desktop-client-javafx/**`
- `apps/launcher-javafx/**`
- `SERVICE_CATALOG.md`
- `PROJECT_REALITY.md`
- `ARCHITECTURE_EXPLAINED.md`
- `docs/modules-overview.md`
- `docs/architecture.md`

## 1. Decision Translation

### Current reality

Jarvis currently ships two separate JavaFX/Kotlin desktop applications:

- `apps/desktop-client-javafx`
- `apps/launcher-javafx`

They are built as separate shaded jars and entered through separate JavaFX application classes:

- `org.jarvis.desktop.DesktopApplication`
- `org.jarvis.launcher.LauncherApplication`

The current separation is functional, but it splits one desktop product across:

- separate window lifecycles
- separate navigation models
- separate visual styles
- separate state models
- separate packaging outputs

### Target state

The target is one unified JavaFX desktop product shell that:

- keeps backend services unchanged
- keeps launcher/runtime concerns visible
- keeps assistant usage separate from diagnostics-heavy flows
- reuses current desktop code wherever that is cheaper and safer than rewriting

### First-implementation scope

The first implementation is the smallest version of the unified desktop shell that is worth shipping internally.

In scope:

| Area | First implementation |
| --- | --- |
| Desktop container | new unified shell with top bar, left navigation, routed main content |
| Auth bootstrap | reuse current desktop login/auth flow |
| Shared config | reuse `AppConfig`, `DesktopConfigService`, `DesktopConfigResolver`, `ResolvedDesktopConfig` |
| Shared runtime summary | reuse `DesktopRuntimeMonitor` and `LocalRuntimeHealthProbe` |
| Shared voice state | reuse `VoiceControlService`, `VoiceSession`, `VoiceWebSocketClient`, `VoiceRuntimeState` |
| Runtime/ops controls | bring launcher runtime actions into a dedicated Diagnostics route |
| First routes | `Home`, `Assistant`, `Voice`, `Diagnostics`, `Settings` |
| Additional routes if time allows | `Devices`, `Life`, `Analytics`, `PC Control` |
| Theme | one real dark theme with centralized JavaFX CSS tokens |

### Out of scope

Out of scope for the first implementation:

- backend service refactors
- merging launcher logic into backend services
- a new mobile or web client
- multi-turn LLM chat UX that requires a new desktop-specific protocol
- memory, security, planner, or smart-home dedicated routes without backed client code
- deleting `desktop-client-javafx` or `launcher-javafx` immediately
- extracting a fully separate Maven design-system module before the shell is stable
- rewriting the desktop stack away from Kotlin + JavaFX

### Explicit assumptions

- Assumption: the unified shell will stay on Kotlin + JavaFX 21 because both current desktop apps already use that stack.
- Assumption: the initial Assistant route will use the existing `/api/v1/orchestrator/execute` string-returning contract, because that is the only current desktop-friendly text-command contract already used by `PcControlTab`.
- Assumption: diagnostics MVP will combine two existing health models instead of inventing a new third one:
  - launcher runtime health via `HealthCheckService`
  - client-visible endpoint health via `DesktopServiceHealthChecker`

## 2. Current Desktop Surface Inventory

### `desktop-client-javafx`

#### Entrypoints and startup

| Asset | Current role | Notes |
| --- | --- | --- |
| `apps/desktop-client-javafx/src/main/kotlin/org/jarvis/desktop/DesktopApplication.kt` | main desktop application | owns startup, auth verification, `TabPane`, runtime polling, PC control WS bootstrap |
| `apps/desktop-client-javafx/src/main/resources/fxml/LoginView.fxml` | login/register view | only FXML surface in the current desktop client |
| `apps/desktop-client-javafx/src/main/kotlin/org/jarvis/desktop/controller/LoginController.kt` | login/register controller | starts `DesktopApplication` again after successful auth |
| `apps/desktop-client-javafx/src/main/resources/css/login.css` | login-only styling | current custom CSS exists only for the login screen |

#### Current tabs and screens

| Current tab | File | Current purpose | Backend / state dependency |
| --- | --- | --- | --- |
| `Home` | `ui/tabs/HomeTab.kt` | runtime summary and recent assistant activity | `DesktopRuntimeMonitor`, `AppConfig`, `VoiceUxStatus` |
| `Voice` | `ui/tabs/VoiceTab.kt` | voice control, transcript, response, wake word, push-to-talk | `VoiceSession`, `VoiceControlService`, `VoiceWebSocketClient`, `AudioRecorder`, `AudioPlayer`, `DesktopRuntimeMonitor` |
| `Devices` | `ui/tabs/DevicesTab.kt` | smart-home device list and actions | `ApiClient`, `/smarthome/devices` |
| `PC Control` | `ui/tabs/PcControlTab.kt` | local desktop actions and one text command box | `SystemControlService`, `ApiClient`, `/orchestrator/execute` |
| `Life` | `ui/tabs/LifeTab.kt` | finance add/list and time tracking | `ApiClient`, `/life/finance/expenses`, `/life/time/*` |
| `Analytics` | `ui/tabs/AnalyticsTab.kt` | expense/time/calendar summaries | `ApiClient`, `/analytics/*` |
| `Settings` | `ui/tabs/SettingsTab.kt` | endpoints, locale, service probes, about, logout | `AppConfig`, `DesktopServiceHealthChecker`, `TokenManager` |

#### Shared client plumbing already present

| Area | Files | What already exists |
| --- | --- | --- |
| Auth/session | `auth/TokenManager.kt`, `service/AuthService.kt` | token persistence, refresh, username/role/userId access |
| Endpoint/config state | `config/AppConfig.kt`, `DesktopConfigService.kt`, `DesktopConfigResolver.kt`, `ResolvedDesktopConfig.kt`, `DesktopSettings.kt` | resolved API/WS URLs, locale, config listeners, persisted settings |
| Runtime summary state | `runtime/DesktopRuntimeMonitor.kt`, `runtime/LocalRuntimeHealthProbe.kt` | backend/voice/pc-control connection state plus event feed |
| Voice state model | `model/VoiceRuntimeState.kt`, `VoiceState.kt`, `VoiceUxStatus.kt`, `VoiceActionAvailability.kt`, `VoiceEventClassifier.kt` | structured voice runtime snapshot and derived UI state |
| Voice control plumbing | `service/VoiceControlService.kt`, `VoiceSession.kt`, `VoiceWebSocketClient.kt`, `WakeWordDetector.kt` | reusable voice orchestration layer already exists |
| Client-visible diagnostics | `service/DesktopServiceHealthChecker.kt` | probes gateway, auth context, smart-home API, life API, analytics API, voice WS, pc-control WS |

#### Current desktop-client constraints

| Constraint | Why it matters |
| --- | --- |
| `DesktopApplication` owns too much startup and shell logic | it must be split before the unified shell is maintainable |
| main UI is a plain `TabPane` | no real routing, no top bar, no nav shell |
| almost all tab styling is inline | design system reuse is poor today |
| there is no dedicated Assistant screen | strategy has an Assistant mode, but current reality only has voice response text and one text-command input inside `PcControlTab` |
| theme handling is inconsistent | login has custom CSS; the rest is code-built inline styling |
| voice lifecycle is stateful and already good | this is a reuse opportunity, not a rewrite target |

### `launcher-javafx`

#### Entrypoints and startup

| Asset | Current role | Notes |
| --- | --- | --- |
| `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherApplication.kt` | launcher application | monolithic UI + runtime control + diagnostics actions |
| `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/JarvisPaths.kt` | path/runtime-mode resolver | chooses `jarvis-launch.sh` vs `scripts/runtime-up.sh`, resolves logs, pids, config |
| `apps/launcher-javafx/src/main/kotlin/org/jarvis/launcher/LauncherConfig.kt` | launcher settings persistence | stores LLM/memory/GPU toggles |

#### Current launcher UI surface

| Surface | Where it lives | Current purpose |
| --- | --- | --- |
| `Control` tab | `LauncherApplication.createUI()` | runtime status, start/stop/start all/stop all, start desktop, feature toggles, action buttons, quick status log |
| `Logs` tab | `LogViewer.kt` and `LauncherApplication.createUI()` | live log file viewer for `launcher.log` and `backend-launch.log` |
| dialogs | `LauncherApplication.kt` | confirmation, error, info, reset choice, diagnostics copied/saved |

#### Runtime and diagnostics pieces already present

| Area | Files | What already exists |
| --- | --- | --- |
| Runtime lifecycle | `ProcessRunner.kt` | guarded backend process start/stop, output streaming, pid/lock handling |
| Health model | `HealthCheckService.kt` | launcher status machine with `IDLE`, `STARTING`, `READY`, `DEGRADED`, `ERROR` |
| Diagnostics export | `DiagnosticsCollector.kt` | text snapshot with masked logs and k8s status |
| Log tailing | `LogViewer.kt` | auto-refreshing log viewer |
| Secret masking | `SecurityUtils.kt` | masks tokens/secrets in diagnostics/log display |

#### Current launcher constraints

| Constraint | Why it matters |
| --- | --- |
| `LauncherApplication` is UI-heavy and monolithic | logic and presentation must be separated before migration |
| launcher health is not a full product-wide health matrix | it mainly covers `api-gateway`, `security-service`, and optional `voice-gateway`, `llm-service`, `memory-service` |
| launcher UI is code-only and inline styled | no shared theme/design system exists |
| runtime actions assume Linux/k3s/docker tooling | diagnostics route must preserve that operational honesty |

### Reuse vs rewrite

| Asset | Reuse strategy |
| --- | --- |
| `TokenManager`, `AppConfig`, `DesktopConfigResolver`, `ResolvedDesktopConfig`, `DesktopSettings` | direct reuse |
| `DesktopRuntimeMonitor`, `LocalRuntimeHealthProbe` | direct reuse |
| `VoiceRuntimeState`, `VoiceUxStatus`, `VoiceActionAvailability`, `VoiceEventClassifier` | direct reuse |
| `VoiceControlService`, `VoiceSession`, `VoiceWebSocketClient`, `PcControlWebSocketClient` | direct reuse with packaging cleanup |
| `DesktopServiceHealthChecker` | direct reuse |
| `HealthCheckService`, `ProcessRunner`, `JarvisPaths`, `LauncherConfig`, `DiagnosticsCollector`, `LogViewer`, `SecurityUtils` | reuse after extraction away from `LauncherApplication` |
| `HomeTab`, `VoiceTab`, `SettingsTab` | adapt first, then refactor into shell-native views |
| `DevicesTab`, `LifeTab`, `AnalyticsTab`, `PcControlTab` | carry over after shell is stable |
| `DesktopApplication`, `LauncherApplication` | rewrite as shell bootstrap + feature view composition |

## 3. Target App-Shell Blueprint

### Shell frame

The unified desktop shell should be a single `Scene` rooted in a JavaFX `BorderPane`.

Recommended region layout:

| Region | Target role | Current source |
| --- | --- | --- |
| `top` | top app bar | new shell code |
| `left` | primary navigation rail / drawer | new shell code |
| `center` | route host | new shell code |
| `right` | optional status/context pane | proposed addition; no current direct equivalent |

### Window structure

The first implementation should use one primary stage with three app modes expressed as routes and chrome state, not as separate processes:

- user mode routes: `Home`, `Assistant`, `Voice`, `Devices`, `Life`, `Analytics`
- system mode routes: `Diagnostics`, `Settings`
- auth mode: `Login`

The shell should keep the window stable while route content changes. It should not replace the entire scene on every navigation event the way `DesktopApplication` currently swaps from login to `TabPane`.

### Top app bar

The top app bar should contain:

| Zone | Content | Backing source |
| --- | --- | --- |
| left | app identity, current route title | shell route state |
| center | global command/search field | proposed addition |
| right | backend status pill, voice status pill, optional AI status pill, profile/logout menu | `DesktopRuntimeMonitor`, `HealthCheckService`, `TokenManager`, launcher settings |

Rules:

- the top bar is status-first, not marketing-first
- runtime status belongs here as compact pills, not as a verbose log line
- the top bar must not expose destructive ops directly except from an explicit menu or Diagnostics route

### Left navigation

The first implementation top-level routes should be:

| Group | Routes |
| --- | --- |
| Core use | `Home`, `Assistant`, `Voice` |
| Current feature carry-over | `Devices`, `Life`, `Analytics`, `PC Control` |
| System | `Diagnostics`, `Settings` |

`Planner`, `Memory`, `Security`, and `Smart Home` should not appear as standalone top-level routes until they have dedicated client views. Current repo reality does not justify those routes yet.

### Main content area

The center content host should:

- own lazy route loading
- keep route controllers alive only when that avoids expensive reconnects
- support a full-screen content surface for `Voice` and `Diagnostics`
- support internal subsections for `Diagnostics` and `Settings`

Implementation rule:

- use a small internal route host abstraction
- do not add a generic plugin/router framework
- a route enum plus `Map<Route, Node>` cache is enough for the first implementation

### Right-side status/context pane

Current reality:

- no dedicated right pane exists

Target state:

- optional pane, collapsed by default
- enabled first for `Home` and `Diagnostics`
- used for secondary context only

First implementation content:

- `Home`: runtime detail and recent events
- `Diagnostics`: selected health item details or last 20 log lines

The right pane is not an MVP blocker. The shell should support it structurally, but it does not need to be fully populated in the first coding pass.

### Global command/search entry

Current reality:

- no global command palette exists
- the only text command field is local to `PcControlTab` and submits to `/api/v1/orchestrator/execute`

Target state for first implementation:

- the global input is a route/search launcher, not a general AI chat box
- it should support:
  - route jump by label
  - quick runtime actions: `start backend`, `stop backend`, `refresh health`
  - opening Diagnostics directly

The command entry should not pretend to be an Assistant chat surface until the dedicated Assistant route exists.

### Status indicators

The shell should expose these global status indicators:

| Indicator | Source |
| --- | --- |
| backend overall | `HealthCheckService` and/or `LocalRuntimeHealthProbe` |
| voice channel | `DesktopRuntimeMonitor.voice` |
| desktop action channel | `DesktopRuntimeMonitor.pcControl` |
| auth/session | `TokenManager` |
| optional AI flags | launcher settings (`enableLlm`, `enableMemory`, `enableGpu`) |

### Notifications and profile

Current reality:

- there is no notification center
- profile/logout exists only inside `SettingsTab`

First implementation:

- top-right profile menu shows username and `Logout`
- no notification inbox is added yet
- important runtime messages stay in `Home` and `Diagnostics`, not in a fake notification feature

## 4. Screen-by-Screen Blueprint

### Home

#### Current reality

`HomeTab.kt` already acts as a dashboard for:

- signed-in user
- active API endpoint
- backend/voice/pc-control status
- recent assistant activity feed

#### Target composition

Main blocks:

- page header with user and active environment
- three runtime summary cards:
  - backend
  - voice
  - desktop actions
- overall runtime headline
- quick actions row
- recent assistant activity timeline

Primary action:

- `Refresh runtime`

Secondary actions:

- open `Voice`
- open `Diagnostics`
- open `Settings`

State variants:

- `unknown`
- `connecting`
- `connected`
- `degraded`
- `disconnected`
- `error`
- `empty activity feed`

Empty/loading/error/degraded behavior:

- when no events exist, show a proper empty state instead of a blank text area
- when runtime is degraded or disconnected, keep the page usable and show the issue inline

Reuse path:

- reuse `HomeTab` rendering logic
- keep `DesktopRuntimeMonitor` as the backing source
- replace inline labels/grid layout with shell-native cards and status pills

### Assistant

#### Current reality

There is no dedicated Assistant screen in the current desktop client.

What exists today:

- voice responses render into `VoiceTab.responseArea`
- assistant events render into `HomeTab.recentEventsArea`
- `PcControlTab` contains a text field that POSTs to `/api/v1/orchestrator/execute`

#### Target composition

Main blocks:

- local session transcript list
- command input composer
- send button
- optional result card area for structured follow-up later

Primary action:

- submit a text command

Secondary actions:

- reuse last command
- clear session
- open `Voice`

State variants:

- empty session
- sending
- success
- unauthorized/session expired
- backend unavailable

Empty/loading/error/degraded behavior:

- empty state shows example commands, not lorem ipsum
- sending shows composer disabled with progress
- backend errors should route the user to Diagnostics, not hide the failure

Implementation rule for first version:

- bind Assistant to `/api/v1/orchestrator/execute`
- treat the response as a plain string
- keep the screen single-turn and session-local

Not in the first Assistant implementation:

- multi-turn LLM chat
- WebSocket chat
- tool cards inferred from model output
- pinned memory context

Reuse path:

- extract the command submit logic from `PcControlTab.createTextCommandBox()`
- wrap it in a dedicated `AssistantView`

Gap:

- if richer assistant UX is needed later, a dedicated desktop-side client for `llm-service` must be added explicitly

### Voice

#### Current reality

`VoiceTab.kt` already contains the richest stateful UX in the desktop client.

It includes:

- connection status
- guidance and device info
- always-listening toggle
- push-to-talk
- cancel button
- transcript area
- response area
- wake-word model fallback behavior

#### Target composition

Main blocks:

- voice status header
- microphone/device summary
- voice control actions
- live transcript panel
- response panel

Primary action:

- start a voice session

Secondary actions:

- cancel session
- refresh devices
- toggle always-listening mode

State variants:

- disconnected
- connecting
- listening for wake word
- listening to command
- processing
- TTS playback
- cooldown
- STT unavailable
- TTS unavailable
- microphone unavailable
- wake-word unavailable
- session error

Empty/loading/error/degraded behavior:

- disconnected and degraded states should keep manual controls visible
- when wake word is unavailable, the screen must clearly fall back to push-to-talk
- STT unavailable must be surfaced as a real degraded state, not just an error dialog

Reuse path:

- reuse `VoiceTab` behavior and state model
- keep `VoiceControlService`, `VoiceSession`, `VoiceWebSocketClient`, and `VoiceRuntimeState`
- refactor `VoiceTab` from `Tab` ownership to `Node` ownership

Migration caution:

- voice lifecycle cleanup is sensitive
- do not recreate voice service instances on every route switch
- the unified shell should keep a single shared voice controller instance alive

### Diagnostics

#### Current reality

Diagnostics are split today across:

- launcher `Control` tab
- launcher `Logs` tab
- launcher dialogs and quick status log
- desktop `SettingsTab` service checker

There is no single unified diagnostics screen.

#### Target composition

Main blocks:

- runtime control panel
- launcher health panel
- client endpoint health panel
- feature toggles panel
- diagnostics actions panel
- live logs panel

Primary action:

- `Start All` when runtime is down
- `Refresh Health` when runtime is already running

Secondary actions:

- `Stop All`
- `Start Desktop`
- `Fix TLS`
- `Reset Jarvis`
- `Disk Cleanup`
- `Enable GPU`
- `Run Acceptance`
- `Collect Diagnostics`
- `Copy Diagnostics`
- open logs folder

State variants:

- `IDLE`
- `STARTING`
- `READY`
- `DEGRADED`
- `ERROR`
- per-endpoint `ONLINE`
- per-endpoint `OFFLINE`
- per-endpoint `UNAUTHORIZED`

Important implementation rule:

- keep launcher runtime health and desktop endpoint health as separate sections
- do not flatten them into one boolean

Why:

- `HealthCheckService` is startup/runtime-focused
- `DesktopServiceHealthChecker` is client-usability-focused

Reuse path:

- extract runtime actions and state from `LauncherApplication`
- reuse `HealthCheckService`, `ProcessRunner`, `JarvisPaths`, `LauncherConfig`, `DiagnosticsCollector`, `LogViewer`, and `DesktopServiceHealthChecker`

Migration caution:

- `LauncherApplication` currently mixes runtime logic and UI node creation
- diagnostics migration requires splitting service logic first, not just copying the current control tab wholesale

### Settings

#### Current reality

`SettingsTab.kt` already contains:

- API gateway URL
- language selection
- resolved endpoint display
- service status check
- about section
- logout action

It also contains a nonfunctional theme selector with `Light` and `Dark`, while the actual app has no consistent theme system outside login.

#### Target composition

Main blocks:

- general settings
- endpoints and environment
- service probe results
- account/profile
- about/build info

Primary action:

- `Save settings`

Secondary actions:

- `Check services`
- `Logout`
- `Reset to defaults` as a proposed addition

State variants:

- valid config
- invalid config
- service check running
- service check results loaded
- unauthorized/session expired

Empty/loading/error/degraded behavior:

- invalid config should be validated inline
- service failures should be shown as structured results, not as one red label only

Reuse path:

- reuse `SettingsTab` logic and `AppConfig`
- keep `DesktopServiceHealthChecker`
- remove the fake light theme option in the unified shell

### Additional carry-over routes justified by current code

| Route | Current source | Carry-over decision |
| --- | --- | --- |
| `Devices` | `DevicesTab.kt` | keep as a separate route after shell baseline is stable |
| `PC Control` | `PcControlTab.kt` | keep as a separate advanced route; Linux-specific and dependency-sensitive |
| `Life` | `LifeTab.kt` | keep as route; current screen is finance/time only, not planner |
| `Analytics` | `AnalyticsTab.kt` | keep as route; useful read-only dashboard |

Routes not justified yet:

- `Planner`
- `Memory`
- `Security`
- `Smart Home` as a separate route distinct from `Devices`

These can be added later after dedicated client surfaces exist.

## 5. Navigation and Routing Model

### Top-level route model

The first shell route enum should be limited to routes that already have backing code or a concrete first-step implementation:

| Route | Type | Backing source |
| --- | --- | --- |
| `LOGIN` | auth | `LoginView.fxml`, `LoginController.kt` |
| `HOME` | existing | `HomeTab.kt` |
| `ASSISTANT` | proposed addition | extracted orchestrator text command flow |
| `VOICE` | existing | `VoiceTab.kt` |
| `DEVICES` | existing | `DevicesTab.kt` |
| `PC_CONTROL` | existing | `PcControlTab.kt` |
| `LIFE` | existing | `LifeTab.kt` |
| `ANALYTICS` | existing | `AnalyticsTab.kt` |
| `DIAGNOSTICS` | shell-native | launcher and service-probe composition |
| `SETTINGS` | existing | `SettingsTab.kt` |

### Nested route model

Only two top-level routes should own nested subsections in the first implementation:

| Top-level route | Nested sections |
| --- | --- |
| `DIAGNOSTICS` | `Overview`, `Logs`, `Actions` |
| `SETTINGS` | `General`, `Endpoints`, `Account`, `About` |

Nested routing can be handled with local tab/segmented controls inside each route. A second global router is unnecessary.

### Route transitions

| Event | Transition |
| --- | --- |
| app launch without token | `LOGIN` |
| app launch with token | `HOME` |
| login success | `HOME` |
| logout | clear session, return to `LOGIN` |
| backend start/stop | stay on current route, update global state |
| endpoint change | stay on current route, refresh dependent services |
| voice state change | stay on current route, update global pills and Voice route if open |

### Assistant UX vs Diagnostics UX separation

This separation is mandatory:

- `Assistant`, `Voice`, `Home`, `Devices`, `Life`, and `Analytics` are usage routes
- `Diagnostics` and `Settings` are system routes

Implementation rule:

- usage routes live in the upper navigation group
- system routes live in a lower navigation group separated visually
- runtime controls stay inside `Diagnostics`, not in the top bar and not on every page

## 6. UI State Model

### Global state containers

The unified shell needs explicit app-level state owners. Current code already has the raw pieces, but not the shell-level composition.

| State | Current source | Target owner | Scope |
| --- | --- | --- | --- |
| auth/session | `TokenManager` | `ShellSessionState` wrapper | global |
| resolved endpoint config | `AppConfig`, `DesktopConfigService` | `ShellConfigState` wrapper | global |
| runtime summary | `DesktopRuntimeMonitor` | `ShellRuntimeState` | global |
| voice runtime | `VoiceControlService` + `VoiceRuntimeState` | `ShellVoiceState` | global |
| runtime lifecycle / backend ops | launcher `status`, `HealthCheckService`, `ProcessRunner`, `LauncherConfig` | `ShellDiagnosticsState` | global |
| current route | none | `ShellNavigationState` | global |
| service probes | `DesktopServiceHealthChecker` | `DiagnosticsViewState` | diagnostics-local |

### Feature-local state

| Feature | Local state |
| --- | --- |
| `Home` | derived from `DesktopRuntimeMonitor.Snapshot` |
| `Assistant` | command input, local conversation list, in-flight flag |
| `Voice` | mostly derived from `VoiceRuntimeState`; local view state should stay thin |
| `Devices` | device list, loading flag, last action status |
| `Life` | expense form values, expense list, time-action status |
| `Analytics` | last payload for each analytics section and loading flag |
| `Diagnostics` | selected diagnostics tab, latest endpoint checks, selected log file |
| `Settings` | edited config form state before save |

### State ownership rules

- auth state does not live inside feature views
- route state does not live inside feature views
- runtime lifecycle state does not live inside `Home`
- voice transport state does not live inside `Diagnostics`
- diagnostics actions do not write directly into random UI labels; they update shared state and logs

### Current-state gap

Current gap:

- launcher runtime state and desktop runtime state are separate models with no shared shell wrapper

Required target state:

- keep the two models separate internally
- expose a shell-level read model that can render:
  - backend overall
  - client channel health
  - optional AI flags
  - recent events

## 7. Design System Blueprint

### Design token table

Use JavaFX looked-up colors and token-style constants in a single shared stylesheet.

#### Color tokens

| Token | Value | Use |
| --- | --- | --- |
| `-jarvis-bg` | `#0B0F14` | app background |
| `-jarvis-surface` | `#121821` | base panels |
| `-jarvis-surface-elevated` | `#18212B` | cards, drawers, raised panes |
| `-jarvis-outline` | `#2A3441` | borders and dividers |
| `-jarvis-primary` | `#7CC7FF` | primary actions and active nav |
| `-jarvis-secondary` | `#A78BFA` | AI/memory smart accents |
| `-jarvis-success` | `#34D399` | healthy/ready state |
| `-jarvis-warning` | `#FBBF24` | degraded/warning state |
| `-jarvis-error` | `#F87171` | failure/error state |
| `-jarvis-text-primary` | `#E6EDF3` | primary text |
| `-jarvis-text-secondary` | `#9FB0C3` | secondary text |

#### Size and spacing tokens

| Token | Value | Use |
| --- | --- | --- |
| `-jarvis-space-1` | `4px` | tight spacing |
| `-jarvis-space-2` | `8px` | default control spacing |
| `-jarvis-space-3` | `12px` | section spacing |
| `-jarvis-space-4` | `16px` | card padding |
| `-jarvis-space-5` | `24px` | page padding |
| `-jarvis-space-6` | `32px` | large layout spacing |
| `-jarvis-radius-sm` | `8px` | inputs, chips |
| `-jarvis-radius-md` | `12px` | cards |
| `-jarvis-radius-lg` | `16px` | larger surfaces |

#### Typography tokens

| Token | Value | Use |
| --- | --- | --- |
| `-jarvis-font-display` | `28px` | page title |
| `-jarvis-font-section` | `20px` | section title |
| `-jarvis-font-card` | `16px` | card title |
| `-jarvis-font-body` | `14px` | default body |
| `-jarvis-font-caption` | `12px` | metadata, pills |

### Elevation rules

Use restrained elevation:

- base surfaces use background + outline only
- elevated surfaces use slightly lighter surface color
- drop shadows are subtle and limited to dialogs and top bar separation

Avoid:

- neon glows as default
- blurred translucent panels everywhere
- deep shadow stacks

### Iconography rules

Current reality:

- both desktop UIs use text-heavy labels and many emoji buttons

Target rule:

- no new emoji-based controls in the unified shell
- nav items use consistent icons plus text
- actions keep text labels unless the icon meaning is unambiguous

### Motion rules

First implementation motion:

- route fade or fade+slide under 180ms
- hover state under 120ms
- no bouncing, spring, or pulse animation

Motion is supportive, not decorative.

### Base component catalog

The first design-system layer should implement these components:

- `JarvisButton`
- `JarvisIconButton`
- `JarvisNavItem`
- `JarvisCard`
- `JarvisSurface`
- `JarvisStatusPill`
- `JarvisTextField`
- `JarvisSearchBar`
- `JarvisSectionHeader`
- `JarvisEmptyState`
- `JarvisSkeleton`
- `JarvisToggle`
- `JarvisBadge`

### Feature-level component catalog

The first shell should add these feature components as thin wrappers over current needs:

- `RuntimeSummaryCard`
- `ActivityFeedPanel`
- `VoiceStatusPanel`
- `TranscriptPanel`
- `ResponsePanel`
- `EndpointHealthTable`
- `RuntimeControlPanel`
- `DiagnosticsActionPanel`
- `LogViewerPanel`

### JavaFX CSS and theming rules

JavaFX-specific implementation rules:

- use one shared stylesheet for the unified shell
- use looked-up colors on the root container
- use style classes, not inline styles, for all new shell code
- use pseudo-classes for stateful controls like nav items and status pills
- keep the existing login CSS only until login is migrated into the new shell theme

Do not continue the current pattern of hard-coded inline colors in each screen.

## 8. Migration Blueprint

### Migration principle

The migration must preserve a working repo at every step.

That means:

- do not delete current clients first
- do not move all classes in one batch
- do not start with launcher UI rewrite before the shell exists

### Step 1. Create the unified shell module

Proposed addition:

- `apps/desktop-app-javafx`

Why first:

- it keeps current clients working
- it gives the migration a safe landing zone
- it avoids destabilizing `desktop-client-javafx` while the shell is still forming

Initial contents:

- JavaFX/Kotlin module skeleton
- top-level application class
- theme bootstrap
- route enum
- placeholder shell scene

### Step 2. Bring over shared non-UI core

First classes to carry over with minimal change:

- `TokenManager`
- `AppConfig`
- `DesktopConfigService`
- `DesktopConfigResolver`
- `ResolvedDesktopConfig`
- `DesktopSettings`
- `DesktopRuntimeMonitor`
- `LocalRuntimeHealthProbe`
- `DesktopServiceHealthChecker`
- `VoiceRuntimeState` model layer
- `VoiceControlService`
- `VoiceSession`
- `VoiceWebSocketClient`
- `VoiceWebSocketMessageFactory`
- `WakeWordDetector`
- `PcControlWebSocketClient`
- `JarvisPaths`
- `LauncherConfig`
- `ProcessRunner`
- `HealthCheckService`
- `DiagnosticsCollector`
- `LogViewer`
- `SecurityUtils`

Rule:

- carry over stable logic first
- do not couple that move to a visual redesign

### Step 3. Build shell-native chrome

Implement:

- top app bar
- left nav
- center route host
- shared status pill area

At this step, route content can still be placeholders.

### Step 4. Port `Home`, `Voice`, and `Settings` through adapter layers

These are the first views to bring into the shell because they already map well to the strategy:

- `HomeTab`
- `VoiceTab`
- `SettingsTab`

Adapter rule:

- convert each screen from owning `Tab` to exposing a `Node`
- keep their internal service wiring temporarily
- remove inline styling only where the shell needs consistency immediately

### Step 5. Split launcher runtime logic from launcher UI

Before Diagnostics can be migrated cleanly, `LauncherApplication` must be decomposed into smaller shell-usable pieces:

- runtime control service
- runtime action handlers
- diagnostics view builder
- log viewer panel

Direct candidate classes:

- `HealthCheckService`
- `ProcessRunner`
- `JarvisPaths`
- `LauncherConfig`
- `DiagnosticsCollector`
- `LogViewer`

Rewrite target:

- `LauncherApplication.createUI()`

### Step 6. Build `Diagnostics` as a shell-native route

Compose Diagnostics from extracted pieces:

- launcher health
- runtime controls
- feature toggles
- diagnostics actions
- logs
- desktop endpoint probes

Do not port launcher `Control` tab as one giant panel unchanged.

### Step 7. Add the first `Assistant` route

The first Assistant route should be intentionally narrow:

- text input
- command history in-session
- `/api/v1/orchestrator/execute` submission
- string response rendering

This should happen after shell, Home, Voice, Settings, and Diagnostics are already stable.

### Step 8. Move additional carry-over routes

In order:

1. `Devices`
2. `Analytics`
3. `Life`
4. `PC Control`

Reason:

- `Devices` and `Analytics` are lower-risk read or action surfaces
- `Life` has forms and mutable data
- `PC Control` has Linux utility dependencies and local side effects

### Step 9. Keep compatibility entrypoints during stabilization

While the unified shell matures:

- keep `desktop-client-javafx` buildable
- keep `launcher-javafx` buildable
- keep the old launcher usable as a fallback

Only after feature parity:

- change compatibility entrypoints
- reduce old clients to thin wrappers or retire them

## 9. Proposed Package and Module Structure

### Immediate structure

Start with one new Maven module and clear package boundaries inside it.

```text
apps/desktop-app-javafx
  src/main/kotlin/org/jarvis/desktop/app/
    DesktopAppApplication.kt
    DesktopAppBootstrap.kt
  src/main/kotlin/org/jarvis/desktop/shell/
    ShellRoot.kt
    ShellRoute.kt
    ShellNavigator.kt
    ShellState.kt
  src/main/kotlin/org/jarvis/desktop/design/
    JarvisTheme.kt
    components/
  src/main/kotlin/org/jarvis/desktop/auth/
  src/main/kotlin/org/jarvis/desktop/config/
  src/main/kotlin/org/jarvis/desktop/runtime/
  src/main/kotlin/org/jarvis/desktop/voice/
  src/main/kotlin/org/jarvis/desktop/diagnostics/
  src/main/kotlin/org/jarvis/desktop/features/home/
  src/main/kotlin/org/jarvis/desktop/features/assistant/
  src/main/kotlin/org/jarvis/desktop/features/voice/
  src/main/kotlin/org/jarvis/desktop/features/diagnostics/
  src/main/kotlin/org/jarvis/desktop/features/settings/
  src/main/kotlin/org/jarvis/desktop/features/devices/
  src/main/kotlin/org/jarvis/desktop/features/life/
  src/main/kotlin/org/jarvis/desktop/features/analytics/
```

### Package ownership rules

| Package area | Ownership |
| --- | --- |
| `auth` | token/session bootstrap only |
| `config` | endpoint and locale resolution only |
| `runtime` | runtime monitor and backend health/runtime actions |
| `voice` | voice transport and session lifecycle |
| `diagnostics` | log/diagnostics services |
| `shell` | route host, chrome, navigation, app-level state |
| `features/*` | actual screens and feature-specific presenters/controllers |
| `design` | theme and reusable JavaFX components |

### Why one new Maven module first

One module is the correct starting point because:

- it limits coordination cost
- it keeps packaging simple
- it avoids premature splitting while the shell is still moving

### What can be split later

Only after the unified shell is stable:

- design-system code can be extracted if reuse pressure is real
- shared runtime UI code can be extracted if the old launcher is still kept for long

Do not force desktop UI code into `jarvis-common`. That module is backend infrastructure, not a JavaFX client foundation.

## 10. MVP Implementation Backlog

### Phase 1. Shell bootstrap

- Goal: create a new runnable unified desktop module without breaking current clients.
- Result: `desktop-app-javafx` launches a dark-themed shell with placeholder routes and auth bootstrap.
- Dependencies: root `pom.xml`, JavaFX/Kotlin module setup, `TokenManager`, `AppConfig`, TLS bootstrap logic from `DesktopApplication`.
- Risk: duplicated startup code during the transition.
- Done when: the new app launches, resolves config, and can switch between placeholder `Home`, `Voice`, `Diagnostics`, and `Settings` routes.

### Phase 2. Shared state wiring

- Goal: centralize shell-level auth, config, runtime, and voice state.
- Result: the shell owns app-wide state wrappers around existing monitor/config/session classes.
- Dependencies: `DesktopRuntimeMonitor`, `VoiceControlService`, `HealthCheckService`, `LauncherConfig`.
- Risk: accidental coupling between launcher state and desktop runtime state.
- Done when: top bar status pills react to backend, voice, and desktop-action state without any feature route manually polling them.

### Phase 3. Route adapters for existing screens

- Goal: render real existing desktop functionality inside the shell.
- Result: `Home`, `Voice`, and `Settings` render as shell routes, not as `TabPane` tabs.
- Dependencies: adapter refactor for `HomeTab`, `VoiceTab`, `SettingsTab`.
- Risk: voice lifecycle leaks if route mounting recreates services.
- Done when: login works and those three routes are functional inside the shell.

### Phase 4. Diagnostics route

- Goal: migrate runtime controls and diagnostics into one shell-native system route.
- Result: `Diagnostics` contains runtime status, health probes, runtime actions, logs, and diagnostics export.
- Dependencies: split `LauncherApplication`, reuse `ProcessRunner`, `HealthCheckService`, `LogViewer`, `DiagnosticsCollector`.
- Risk: copying launcher UI wholesale instead of extracting reusable runtime services.
- Done when: the unified shell can start/stop the backend, show launcher status, display logs, and export diagnostics.

### Phase 5. First Assistant route

- Goal: add a concrete assistant-facing text interaction route without inventing a new protocol.
- Result: `Assistant` screen submits text to `/api/v1/orchestrator/execute` and renders response history.
- Dependencies: `ApiClient`, orchestrator contract, extracted text-command logic from `PcControlTab`.
- Risk: user expectation drifts toward full LLM chat although the first implementation is single-turn command execution.
- Done when: the route works against the current backend and handles empty, loading, success, and failure states cleanly.

### Phase 6. Carry-over feature routes

- Goal: reach parity with the current desktop client’s shipped surfaces.
- Result: `Devices`, `Analytics`, `Life`, and `PC Control` are accessible inside the unified shell.
- Dependencies: screen adapters and theme cleanup.
- Risk: Linux-specific `PC Control` actions and inline styles slow down migration.
- Done when: the current feature set of `desktop-client-javafx` is available through the unified shell.

### Phase 7. Compatibility and retirement

- Goal: retire duplicate product surfaces safely.
- Result: one desktop product becomes the main supported entrypoint.
- Dependencies: unified shell parity, packaging, smoke verification.
- Risk: deleting old clients before the new shell covers real operator workflows.
- Done when: `launcher-javafx` is no longer needed as a separate UI, or remains only as a thin compatibility wrapper.

## 11. Risks and Anti-Patterns

### High-risk mistakes

- building a beautiful shell first and postponing runtime controls until later
- rewriting working voice/session logic instead of reusing it
- adding routes for `Planner`, `Memory`, or `Security` before real client views exist
- flattening launcher health and client endpoint health into one false “system healthy” badge
- letting destructive runtime actions leak into every screen

### Overengineering risks

- inventing a generic app-wide event bus
- splitting into many Maven modules before the shell stabilizes
- designing a huge component library before the first shell routes exist
- building a command palette that tries to be search, assistant, and admin console at the same time

### UX anti-patterns

- mixing operator diagnostics into the same visual hierarchy as daily assistant use
- making the top bar a dense cockpit of controls
- keeping emoji-heavy controls as the long-term design language
- treating degraded mode as a fully blocked mode even when core routes still work
- hiding errors in logs instead of surfacing actionable guidance in the UI

### Honest current gaps

- no current dedicated Assistant screen
- no current notification center
- no current right-side context pane
- no current dedicated client routes for planner, memory, or security
- no current unified design system outside the login stylesheet

These gaps must stay visible in implementation planning. They should not be papered over with placeholder routes that look finished but do nothing.

## 12. Recommended Next Coding Step

The next coding step should be:

**Create `apps/desktop-app-javafx` with a real shell frame and port `Home` first by adapting `HomeTab` into a shell route.**

Why this is the correct first move:

- it proves the new module, stage bootstrap, routing, and theme without touching risky voice or runtime-control lifecycles yet
- `HomeTab` already depends on the correct shared state layer: `DesktopRuntimeMonitor`
- it creates the adapter pattern that will be reused by `Voice` and `Settings`
- it keeps current launcher and desktop clients fully operational while the new shell takes shape

What that first step should include:

- new module skeleton in `pom.xml`
- `DesktopAppApplication.kt`
- `ShellRoute` enum
- `ShellRoot` using `BorderPane`
- dark token stylesheet with looked-up colors
- shell navigation for at least `Home`, `Voice`, `Diagnostics`, and `Settings`
- `HomeView` adapted from `HomeTab`

What it should not include yet:

- launcher action migration
- Assistant route
- full design-system extraction
- removal of `desktop-client-javafx` or `launcher-javafx`
