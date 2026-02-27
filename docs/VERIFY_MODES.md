# Verification Modes (prod-only)

**Current script:** `scripts/verify-prod.sh`

## Usage

```bash
./scripts/verify-prod.sh
```

Checks:
- No docker-compose/minikube/NodePort references in active repo
- No dev profiles (`application-dev*`, `application-docker*`)
- No legacy k8s overlays
- No certs/keys committed

## Legacy

Historical iteration scripts are kept in `scripts/legacy/` for reference only.


