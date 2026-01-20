# Jarvis 2.0 — Deployment (prod-only)

## Overview

Single supported path:

```
./jarvis-launch.sh
```

This brings up k3s → ingress-nginx → HTTPS → services.

---

## Prerequisites

- k3s (single-node Kubernetes)
- ingress-nginx (Ingress controller, class `nginx`)
- kubectl
- Docker (for local image builds)
- Java 21, Maven 3.8+

Optional:
- NVIDIA GPU for LLM acceleration

---

## 1) Secrets (local only)

```bash
cp secrets/secrets.example.env ~/.jarvis/secrets/secrets.env
chmod 600 ~/.jarvis/secrets/secrets.env
./scripts/product/jarvis-secrets-apply.sh
```

No secrets are committed to git.

---

## 2) TLS + /etc/hosts

```bash
./scripts/product/jarvis-generate-certs.sh
sudo ./scripts/product/jarvis-install-tls.sh
sudo ./scripts/product/jarvis-setup-hosts.sh
```

---

## 3) Launch

```bash
./jarvis-launch.sh
```

Optional flags:

```bash
# Enable LLM + Memory
ENABLE_LLM=true ENABLE_MEMORY=true ./jarvis-launch.sh

# Disable GPU (CPU fallback)
ENABLE_LLM=true ENABLE_GPU=false ./jarvis-launch.sh

# Skip build
ENABLE_BUILD=false ./jarvis-launch.sh
```

---

## Access

```
API Gateway: https://api.jarvis.local
Voice Gateway: wss://voice.jarvis.local
```

---

## LLM Models

Place models in `~/.jarvis/models`:

```
~/.jarvis/models/
├── h2ogpt-7b-chat-q4_k_m.gguf
└── h2ogpt-4096-llama2-7b-chat/
```

---

## Stop

```bash
./jarvis-stop.sh
```

---

## Troubleshooting

```bash
kubectl get pods -n jarvis
kubectl logs -f deployment/api-gateway -n jarvis
kubectl logs -f deployment/llm-server -n jarvis
kubectl logs -f statefulset/postgres -n jarvis
```
