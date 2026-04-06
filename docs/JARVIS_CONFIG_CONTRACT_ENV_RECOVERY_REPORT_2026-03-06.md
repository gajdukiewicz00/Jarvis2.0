# JARVIS CONFIG CONTRACT & ENV RECOVERY REPORT

## 1. Executive Summary

Primary config/runtime blockers found during this recovery:

- Missing repo contract artifact: `secrets/secrets.example.env` did not exist even though launcher, docs, and Stage 16 baseline expect it.
- Missing tracked `secrets/` scaffold: `.gitignore` ignored the whole `secrets/` directory, so `.gitkeep` / `*.example.env` could not be restored as tracked contract files.
- Broken secret parser: `scripts/product/jarvis-secrets-apply.sh` lost trailing `=` in base64-like values, causing drift between local `~/.jarvis/secrets/secrets.env` and live `secret/jarvis-secrets`.
- Local secrets drift: local secret file did not match live cluster runtime state.

Non-config findings discovered during verification:

- `voice-gateway` is running, but `/models/stt/vosk/vosk-model-small-ru-0.22` is absent in the mounted PVC, so Vosk STT is degraded.
- `analytics-service` overall `/actuator/health` is `DOWN` because its health indicator calls a protected life-tracker endpoint and gets `403`; this is not an env/secrets absence.

## 2. Expected Config Contract

| Name | Where used | Required | Type | Current state | Source of truth | Safe action |
| --- | --- | --- | --- | --- | --- | --- |
| `POSTGRES_USER`, `POSTGRES_PASSWORD` | `postgres`, `postgres-pgvector`, DB-backed services | yes | secret | already present | k8s secret / local secrets file | keep |
| `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | `security-service`, `life-tracker`, `user-profile`, `planner-service`, `memory-service` | yes | secret | already present | k8s secret / local secrets file | keep |
| `SPRING_DATASOURCE_URL` | DB-backed services, default secret template | yes | plain config | already present | deployment env / local secrets template | keep |
| `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS`, `RABBITMQ_ERLANG_COOKIE` | AMQP infra | yes | secret | already present | k8s secret / local secrets file | keep |
| `SPRING_RABBITMQ_USERNAME`, `SPRING_RABBITMQ_PASSWORD` | `pc-control`, `smart-home-service` | yes for AMQP-enabled paths | secret | already present | k8s secret / local secrets file | keep |
| `MQTT_USERNAME`, `MQTT_PASSWORD` | `mosquitto`, `smart-home-service` | yes | secret | already present | k8s secret / local secrets file | keep |
| `JWT_SECRET` | `api-gateway`, `security-service` | yes | secret | already present | k8s secret / local secrets file | keep |
| `SERVICE_JWT_SECRET` | shared Feign/service JWT auth | yes | secret | already present | k8s secret / local secrets file | keep |
| `ENCRYPTION_KEY` | local secret contract only | optional | secret | already present | k8s secret / local secrets file | keep |
| `VOICE_GATEWAY_URL`, `NLP_SERVICE_URL`, `ORCHESTRATOR_URL`, `PC_CONTROL_URL`, `LIFE_TRACKER_URL`, `SMART_HOME_URL`, `ANALYTICS_URL`, `SECURITY_URL`, `LLM_SERVICE_URL`, `USER_PROFILE_URL` | Spring service URL placeholders | yes for corresponding services | URL | already present via defaults / manifests | repo default / runtime state | keep |
| `EMBEDDING_SERVICE_URL`, `MEMORY_SERVICE_URL`, `LLM_SERVER_URL` | optional LLM/memory stack | optional | URL | already present in manifests, workloads scaled to `0` | repo default / manifests | keep |
| `JARVIS_LLM_ENABLED`, `MEMORY_ENABLED`, `ENABLE_LLM`, `ENABLE_MEMORY` | launcher + optional workloads | optional | feature flag | already present and disabled for first boot | runtime state / repo default | keep |
| `ENABLE_GPU`, node GPU allocatable | `llm-server`, launcher GPU setup | optional | feature flag / hardware prereq | present but unavailable (`nvidia.com/gpu=0`) | runtime state | ask human later |
| `JARVIS_VOSK_MODEL_PATH_RU`, `JARVIS_VOSK_MODEL_PATH_EN` | `voice-gateway` STT | yes for STT | file path | present but target missing | repo default / runtime state | ask human later |
| `vosk-models-pvc` | `voice-gateway` | yes for STT | volume mount | bound but empty | runtime state | ask human later |
| `llm-models-pvc` | `llm-server` | yes for LLM stack | volume mount | pending | runtime state | ask human later |
| `KUBECONFIG` / `~/.jarvis/kubeconfig` | launcher, scripts, kubectl | yes for local k8s ops | file path | file exists and valid; shell env not exported by default | runtime state | keep |
| `jarvis-tls`, `~/.jarvis/tls/*`, `/etc/hosts` entries | ingress TLS and desktop/launcher trust | yes | certs / host mapping | already present | runtime state | keep |
| `JARVIS_API_BASE_URL`, `JARVIS_USE_TLS`, `JARVIS_JAVA_TRUSTSTORE`, `JARVIS_JAVA_TRUSTSTORE_PASSWORD` | launcher + desktop | yes for TLS desktop flow | local config | already present via defaults / local files | launcher/runtime state | keep |
| `PORCUPINE_ACCESS_KEY`, `jarvis_ru.ppn` | desktop always-listening wake word | optional | secret / model file | `jarvis_ru.ppn` is committed in desktop resources; access key may be unset | repo for model / human for secret | ask human later |
| `BOOTSTRAP_ADMIN_*` | `security-service` bootstrap admin | optional | secret/plain config | absent | human / template | leave unset |
| `secrets/secrets.example.env` | first-boot secret contract | yes | template | missing before recovery | example template | restore from template |

## 3. Missing / Broken Items

- Missing `secrets/secrets.example.env`.
- Missing tracked `secrets/.gitkeep`.
- `.gitignore` prevented `secrets` contract files from being tracked.
- `jarvis-secrets-apply.sh` parsed `KEY=value=` incorrectly and dropped trailing base64 padding.
- Local `~/.jarvis/secrets/secrets.env` drifted from live `secret/jarvis-secrets`.
- `voice-gateway` mounted `/models` PVC is empty; expected Vosk model paths are absent.
- `llm-models-pvc` is `Pending`; optional LLM stack cannot start normally on this node.
- `PORCUPINE_ACCESS_KEY` is not configured; the repo-local custom wake-word model is committed.

## 4. Recovery Actions

- Added [secrets/secrets.example.env](/home/kwaqa/Jarvis/Jarvis2.0/secrets/secrets.example.env).
- Added [secrets/.gitkeep](/home/kwaqa/Jarvis/Jarvis2.0/secrets/.gitkeep).
- Updated [.gitignore](/home/kwaqa/Jarvis/Jarvis2.0/.gitignore) to re-include `secrets/`.
- Fixed [scripts/product/jarvis-secrets-apply.sh](/home/kwaqa/Jarvis/Jarvis2.0/scripts/product/jarvis-secrets-apply.sh) so values preserve trailing `=`.
- Synced local `~/.jarvis/secrets/secrets.env` from live `secret/jarvis-secrets` without rotating cluster secrets or restarting workloads.

## 5. Generated Secrets and Placeholders

- No new live secrets were generated in this recovery pass.
- No cluster secret values were rotated.
- `secrets/secrets.example.env` contains placeholders only, not real values.
- Remaining placeholders / unknowns:
  - `PORCUPINE_ACCESS_KEY`
  - actual Vosk model contents for `/models/stt/vosk/vosk-model-small-ru-0.22`
  - LLM model payload for `llm-models-pvc`
  - GPU prerequisites if LLM GPU mode is required

## 6. Runtime Verification

- `kubectl --kubeconfig ~/.jarvis/kubeconfig get secrets -n jarvis` shows `jarvis-secrets` and `jarvis-tls`.
- `kubectl --kubeconfig ~/.jarvis/kubeconfig get configmaps -n jarvis` shows `mosquitto-config`, `postgres-init`, `postgres-init-scripts`.
- `kubectl --kubeconfig ~/.jarvis/kubeconfig get deploy -n jarvis` shows all core workloads `READY`; optional LLM/memory workloads remain `0/0`.
- `kubectl --kubeconfig ~/.jarvis/kubeconfig get pods -n jarvis` shows core pods `Running`.
- Local `~/.jarvis/secrets/secrets.env` now matches live `secret/jarvis-secrets` key-for-key.
- `curl --cacert ~/.jarvis/tls/jarvis-ca.crt https://api.jarvis.local/actuator/health` returns `UP`.
- `voice-gateway` direct health returns `UP` via port-forward, but logs warn that Vosk model is missing.
- `analytics-service` direct health returns `DOWN`; logs show `403` from life-tracker health check, indicating a non-env auth/logic issue.
- `/etc/hosts` and ingress IP match `10.113.0.176` for `api.jarvis.local` and `voice.jarvis.local`.
- `jarvis-tls` in cluster matches local `~/.jarvis/tls/jarvis.crt` / `jarvis.key`.

## 7. Remaining Unknowns / Human Input Needed

- Provide or restore Vosk model files into the `vosk-models-pvc` volume if STT must work normally.
- Provide `PORCUPINE_ACCESS_KEY` only if always-listening wake word is required.
- Provide LLM model payload and optionally GPU runtime if enabling `ENABLE_LLM=true`.
- Decide whether the `analytics-service` health indicator should use service auth or a non-protected endpoint; this is outside env recovery.
