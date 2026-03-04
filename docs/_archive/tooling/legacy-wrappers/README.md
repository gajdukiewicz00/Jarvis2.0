# Legacy Wrapper Scripts (Archived)

These scripts were archived from `scripts/` during Cleanup Batch 1 and are no longer part of the active launch surface.

Archived files:
- `deploy.sh`
- `jarvis-k8s-up.sh`
- `stop.sh`
- `generate-certs.sh`

Supported replacements:
- `deploy.sh` -> `./jarvis-launch.sh`
- `jarvis-k8s-up.sh` -> `ENABLE_LLM=true ENABLE_MEMORY=true ./jarvis-launch.sh`
- `stop.sh` -> `./jarvis-stop.sh`
- `generate-certs.sh` -> `./scripts/product/jarvis-generate-certs.sh`

Golden path:
- Launch: `./jarvis-launch.sh`
- Stop: `./jarvis-stop.sh`
- Logs: `./jarvis-logs.sh`
