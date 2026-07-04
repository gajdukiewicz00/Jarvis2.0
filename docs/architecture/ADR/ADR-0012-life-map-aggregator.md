# ADR-0012: Life-map aggregator + JavaFX panel skeleton

## Status

Accepted (Phase 11). UI styling + chart polish deliberately left to the
operator's iterative tuning on a real machine.

## Context

Phases 0-10 turned Jarvis into a working voice-driven, kill-switchable,
audited assistant. None of that surfaces to the user as a "dashboard".
SPEC-1 § Phase 11 demands a desktop "Life Map" panel with seven sections
(Sleep, Finances, Tasks, Home, Activity, Health, Jarvis live feed), a
proactive warning framework, time classification (work/study/rest/sport/
sleep/custom), and the ability for Jarvis to explain its
recommendations.

The pragmatic constraint: the JavaFX shell needs interactive iteration on
a real display to look right. I cannot pixel-tune CSS / FXML / charts
from inside a tool-driven session. So Pass 1 splits the work:

* **Backend** — full data layer + REST surface, fully testable.
* **Desktop** — programmatic JavaFX skeleton with a clean ViewModel
  binding pattern, ready for the operator to swap in styled widgets
  / charts / themes without touching the wire format.

## Decision

### Backend (life-tracker / `lifemap/` package)

* `TimeCategory` — six SPEC values + `CUSTOM` catch-all.
* `LifeMapProperties` — declarative regex rule table per category +
  warning thresholds (`time-waste-minutes-per-day`,
  `overspend-budget-ratio`, `low-sleep-hours`).
* `TimeClassifier` — compiles patterns once, hot-reloads on properties
  change, returns `CUSTOM` when nothing matches (so the panel can
  surface "uncategorised" instead of mis-classifying).
* `InMemoryActivityStore` — per-user `ConcurrentLinkedDeque`, capped at
  5000 entries, indexable by day. Pass 1 is intentionally volatile —
  Phase 12 promotes to a Postgres table once the schema settles.
* `CrossServiceClient` — best-effort HTTP fan-out to planner / vision /
  memory with 1.5s timeouts and graceful zeros on any failure.
* `DailySummaryService` — assembles the cross-service snapshot and
  feeds it into the warning engine.
* `ProactiveWarningEngine` + three rules (`TIME_WASTE`, `OVERSPEND`,
  `LOW_SLEEP`); each warning is registered in an in-memory map so
  `RecommendationExplanationService` can echo the same evidence.
* `LifeMapController` — five REST endpoints
  (`/time-entries`, `/activity`, `/summary`, `/warnings`,
  `/recommendations/{id}/explanation`).
* `FinanceTotals.Provider` + `SleepProvider` — narrow injection points
  so Pass 1 ships an empty default and Phase 12 can wire to the real
  finance JPA / Google Fit adapters without rewriting the summary code.

### Desktop (`apps/desktop-javafx/.../desktop/lifemap/`)

* `LifeMapClient.kt` — OkHttp + Jackson, returns raw `JsonNode` so the
  panel renders best-effort even if the wire shape evolves.
* `LifeMapPanel.kt` — `TabPane` with seven tabs. Each tab uses a tiny
  `SectionViewModel` (a single `SimpleObjectProperty<String>`) that
  is updated from a daemon scheduler every 15s. The "Jarvis live feed"
  tab subscribes to the existing Phase 6 `AgentLiveFeed` and
  re-renders on emit.
* No FXML, no CSS, no charts: this is the **skeleton**. Pass 2
  replaces the `TextArea` snapshots with proper `Chart` / `TableView`
  widgets and hands the panel a real CSS theme.

### Acceptance vs UI polish

The acceptance gate is wire-level + behavioural ("warns about time
waste", "explains recommendation when asked", "summary shows finance /
tasks / activity"). All of those are satisfied by the REST surface and
the skeleton-bound panel. Pixel-perfect rendering is *not* an
acceptance criterion — and is the part most likely to need iteration on
a real display.

## Consequences

* `life-tracker` keeps its existing 12 tests intact; Phase 11 is
  strictly additive in a `lifemap/` sub-package.
* The desktop module gets a new `desktop/lifemap/` package that
  depends only on existing JavaFX, OkHttp and the Phase 6
  `AgentLiveFeed`. No Spring inside the JavaFX side.
* Cross-service calls are fail-soft: if planner / vision / memory are
  unreachable the panel still renders a partial summary instead of
  throwing.
* Warning rules are deterministic and explainable by design — every
  fired warning has a registered evidence record the panel can pull on
  demand. SPEC-1 "Jarvis must explain advice when explicitly asked"
  is satisfied by the explanation endpoint without resorting to LLM
  re-generation (which could hallucinate the rationale).

## Alternatives considered

* **Build the full styled UI now.** Rejected — pixel tuning needs a
  real display and an iterative loop. Skeleton + clean ViewModel is
  the honest deliverable from a tool-driven session.
* **One mega-summary endpoint vs. four.** Five endpoints stay even
  though the dashboard polls `/summary` only — `/activity` and
  `/explanation` are needed for drill-downs and "Jarvis, why?" voice
  flows respectively.
* **Persist activity entries directly to Postgres in Pass 1.**
  Rejected to keep the surface bounded; the in-memory ring buffer is
  enough for end-to-end demo + tests, and Phase 12 already has
  retention work scheduled.
* **Use LLM to write each summary narrative.** Rejected for the
  acceptance gate. Deterministic templates + evidence map are
  testable; LLM narratives are best left for Phase 12 polish.

## References

* SPEC-1 § "Life Map UI"
* SPEC-1 § Phase 11 task list
* `apps/life-tracker/.../lifemap/`
* `apps/desktop-javafx/.../desktop/lifemap/`
* [phase-11-acceptance-evidence.md](../phase-11-acceptance-evidence.md)
