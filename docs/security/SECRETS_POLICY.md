# Secrets Policy

This file is kept because secret handling is policy, not service-overview prose.

## Rules

1. Never commit real secrets to git.
2. Keep local secret values outside the repo.
3. Use templates and apply scripts from this repo, not committed secret manifests.
4. Treat TLS material and auth secrets the same way: local generation, local storage, controlled apply.

## Canonical Local Paths

- local secret env file: `~/.jarvis/secrets/secrets.env`
- local TLS material: `~/.jarvis/tls/`

## Canonical Apply Path

```bash
./scripts/product/jarvis-secrets-apply.sh
```

## Current Secret Families Confirmed From Code And Manifests

- Postgres credentials: `POSTGRES_USER`, `POSTGRES_PASSWORD`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
- auth secrets: `JWT_SECRET`, `SERVICE_JWT_SECRET`
- MQTT credentials: `MQTT_USERNAME`, `MQTT_PASSWORD`
- Grafana admin credentials: `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD`
- TLS material: `jarvis-tls` and local generated cert/truststore files

## Notes

- `secrets/secrets.example.env` remains the template, not a source of real values
- some legacy/optional keys may still exist in templates or helper scripts; manifests and active runtime scripts remain the final source of truth
