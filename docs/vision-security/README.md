# Vision Security

`vision-security-service` is a local Ubuntu-only Jarvis module that monitors the current workstation and answers one narrow question every two seconds: is the enrolled owner in front of the machine, or is there an unknown person?

The MVP is intentionally split into a dedicated local service instead of being mixed into `pc-control`, `desktop-client-javafx`, or unrelated backend services. The module combines:

- webcam face detection
- owner enrollment and owner-vs-unknown verification
- multi-frame unknown debounce
- incident evidence capture
- screenshot OCR and screen-context tagging
- SMTP email alerting
- a shell-native desktop page in `desktop-app-javafx`

## What It Reuses

- Jarvis Maven multi-module build and runtime scripts
- `api-gateway` proxy pattern for desktop-facing REST access
- `desktop-app-javafx` shell routing and read-model UI pattern
- local desktop/X11 conventions already present in `pc-control`
- Jarvis config and actuator health conventions

## MVP Scope

- Platform: Ubuntu local runtime
- Check interval: 2000 ms by default
- Decision states: `OWNER_PRESENT`, `UNKNOWN_PERSON`, `NO_FACE`, `UNCERTAIN`
- Screen understanding: screenshot, OCR, active window title, active process, rule-based tags
- CV report support: deterministic export of original, enhanced, segmentation, cleaned, detection, and final decision images

## Current Integration Points

- Backend service: `apps/vision-security-service`
- Gateway proxy: `apps/api-gateway`
- Desktop page: `apps/desktop-app-javafx`
- Local runtime registration: `scripts/runtime/common.sh`, `scripts/runtime-up.sh`, `scripts/runtime-down.sh`, `scripts/runtime-status.sh`

## Document Map

- [ARCHITECTURE.md](./ARCHITECTURE.md)
- [IMPLEMENTATION_PLAN.md](./IMPLEMENTATION_PLAN.md)
- [API.md](./API.md)
- [RUNTIME.md](./RUNTIME.md)
- [ENROLLMENT.md](./ENROLLMENT.md)
- [INCIDENTS.md](./INCIDENTS.md)
- [SCREEN_ANALYSIS.md](./SCREEN_ANALYSIS.md)
- [DEMO.md](./DEMO.md)
- [REPORT_DRAFT.md](./REPORT_DRAFT.md)
- [FAILURE_CASES.md](./FAILURE_CASES.md)
- [TODO_V2.md](./TODO_V2.md)
