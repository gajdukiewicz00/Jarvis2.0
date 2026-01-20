# jarvis-launch.sh

Prod-only launcher for Jarvis backend. Primary path is the **Launcher UI**; this script is for automation or troubleshooting.

## What it does

- Ensures k3s is running
- Ensures ingress-nginx is installed
- Generates TLS certificates (local CA)
- Applies Kubernetes manifests
- Waits for readiness

## Usage

```bash
./jarvis-launch.sh
```

Optional flags (env):

```bash
ENABLE_LLM=true ENABLE_MEMORY=true ./jarvis-launch.sh
ENABLE_GPU=false ./jarvis-launch.sh
ENABLE_BUILD=false ./jarvis-launch.sh
```

## Endpoints

- https://api.jarvis.local
- wss://voice.jarvis.local

## Related scripts

- `scripts/product/jarvis-generate-certs.sh`
- `scripts/product/jarvis-install-tls.sh`
- `scripts/product/jarvis-setup-hosts.sh`

## Troubleshooting

If Docker build fails with `invalid output path` and `/var/lib/docker` is a symlink:

```
sudo scripts/product/jarvis-fix-docker-root.sh --reset
```
