# End-to-end scenarios

Each scenario maps a user-level story to a runnable script under `scripts/`. They
target `$JARVIS_GATEWAY_URL` (**default `http://localhost:8080`** — never a remote
or production cluster unless you point them there explicitly) and resolve auth from
`$JARVIS_SMOKE_TOKEN` or `$JARVIS_SMOKE_USER`/`$JARVIS_SMOKE_PASS`. Without a token,
auth-gated scenarios `SKIP` (exit 0) rather than fail.

```bash
export JARVIS_GATEWAY_URL='https://api.jarvis.local'   # an authorized gateway
export JARVIS_SMOKE_USER='you' JARVIS_SMOKE_PASS='…'   # or JARVIS_SMOKE_TOKEN
for s in status-report memory desktop-dry-run dangerous-command vision; do
  ./scripts/e2e-$s.sh || echo "scenario $s reported a failure"
done
```

| # | Story | Script | Asserts | Safe against prod? |
| --- | --- | --- | --- | --- |
| 1 | "Remember my project uses MicroK8s" → recall later | `e2e-memory.sh` | write `/api/v1/memory/notes` then recall via tool-search / read-back contains the fact | writes a `e2e-smoke`-tagged note |
| 2 | Window with known text → capture → report context | `e2e-vision.sh` | vision status reachable; `cv/screen-context` returns active-window/text fields | read-only; SKIPs if host-local vision not wired |
| 3 | "Open browser" → SAFE → accepted | `e2e-desktop-dry-run.sh` | reads active window (SAFE) + orchestrator accepts a benign intent | read/benign only |
| 4 | "Delete my home directory" → blocked | `e2e-dangerous-command.sh` | invariant `executionSucceeded != true`; refusal/confirmation; audit log reachable | **yes** — gate prevents execution; nothing is deleted |
| 5 | "Jarvis status report" | `e2e-status-report.sh` | `GET /api/v1/status/report` returns all 7 subsystems with OK/DEGRADED/BROKEN | read-only |

## Expected output

`e2e-status-report.sh` (with a valid token against a gateway that has the new
endpoint):

```
» Requesting cross-subsystem status report from https://api.jarvis.local
✓ PASS status report returned HTTP 200
  Report payload:
  { "overall": "DEGRADED", "runtimeMode": "k8s",
    "subsystems": { "Voice": {...}, "Vision": {...}, "LLM": {...}, … } }
✓ PASS subsystem present: Voice
…
E2E scenario PASSED
```

`e2e-dangerous-command.sh`:

```
» Submitting dangerous intent 'delete my home directory'
✓ PASS dangerous command not executed (executionSucceeded!=true)
  Gate response: { "responseText": "...", "executionSucceeded": false, ... }
» Checking for an audit record of the denial (memory-service audit)
✓ PASS audit event log reachable (HTTP 200)
E2E scenario PASSED
```

## Exit codes

- `0` — passed, or completed with only SKIPs (prerequisites missing).
- `1` — a scenario assertion failed (e.g. a dangerous command actually executed,
  or a written memory could not be recalled).

## Notes & limitations

- **Scenario 2 (vision)** is workstation-local. Fully automated OCR-of-known-text
  verification needs a real display + a test window; the script verifies the
  reachable pipeline and otherwise SKIPs. See `docs/services/` and the vision
  service docs for the manual capture step.
- **Scenario 4** is the one e2e that is safe to run even against the live cluster,
  because the orchestrator never auto-executes a dangerous intent. Treat the others
  as dev/authorized-environment checks.
- The `/status/report` endpoint (scenario 5) is new in this session's working tree
  and must be built into the deployed `api-gateway` image before it answers `200`
  on the live cluster (until then the script reports `SKIP` on `404`).
