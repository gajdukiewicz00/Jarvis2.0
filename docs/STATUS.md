# Release Status

Current status: **ACCEPTED**

## Gates

- `scripts/verify-ai.sh` must exit `0`.
- Acceptance must run via internal port-forward (UI button or `scripts/product/jarvis-run-acceptance.sh`).
- Backend + DB must be ready: `kubectl -n jarvis get pods` shows all core pods `READY 1/1` (including `postgres`).
- UI smoke check: launcher + desktop client open and connect to API.

## Deployment assumptions

- k3s + ingress-nginx (class `nginx`).
- Tool endpoints are internal-only.
- Public ingress must return `401`/`403` for `/api/v1/tools/**` without JWT.

## Acceptance checklist

Primary path: **Launcher → Run Acceptance**.

CLI fallback:
```bash
scripts/verify-ai.sh
scripts/product/jarvis-run-acceptance.sh
```

### UI smoke check

Open **Jarvis 2.0** from the app menu, click **Start All**, then **Start Desktop**.

Expected:
- Launcher opens.
- Start All brings backend to READY.
- Desktop client opens and can log in to API gateway.

## Flyway repair (life-tracker)

If Flyway validation fails due to checksum mismatch (V1–V3), run a repair job
and restart `life-tracker` afterward. See:

- `docs/ops/flyway-repair-lifetracker-job.yaml`
