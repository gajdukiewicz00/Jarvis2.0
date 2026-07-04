# host-model-daemon

`host-model-daemon` is the Kubernetes Service name for llama.cpp processes
running directly on the Linux host. The Service has no selector; traffic is
routed through a manually patched `Endpoints` object whose address is the host
IP.

Production path:

- `llm-service` pod
- `host-model-daemon.jarvis-prod.svc.cluster.local:18080`
- manual Endpoints
- Linux host IP
- llama.cpp OpenAI-compatible server

Ports:

- `18080` main chat model, upstream endpoint `POST /v1/chat/completions`
- `18081` coding model
- `18082` router model

The placeholder Endpoints IP is `192.0.2.1` in git and must be patched during
deployment by `infra/scripts/microk8s/apply-host-endpoints.sh`.
