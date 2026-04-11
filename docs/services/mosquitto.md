# mosquitto

## 1. Name

`mosquitto`

## 2. Type

Runtime MQTT broker / infrastructure component.

## 3. Purpose

Provides MQTT transport for `smart-home-service` in the Kubernetes runtime.

## 4. Current Reality

This broker is defined in `k8s/base/mosquitto/deployment.yaml` and is part of the cluster runtime. The local process runtime does not start a Mosquitto broker; local `smart-home-service` defaults to mock transport instead.

## 5. Entry Points

- MQTT port: `1883`
- WebSocket port: `9001`

## 6. Configuration

Main source artifact:

- `k8s/base/mosquitto/deployment.yaml`

Important settings include:

- `MQTT_USERNAME`
- `MQTT_PASSWORD`
- ACL topic prefix `jarvis/#`
- generated password file from init container `mqtt-password-bootstrap`

## 7. API / WebSocket Surface

No REST API. Runtime interfaces are:

- MQTT on `1883`
- MQTT over WebSockets on `9001`

## 8. Main Internal Components

- Mosquitto deployment
- bootstrap init container for password/ACL generation
- `mosquitto-config` ConfigMap
- `ClusterIP` service `mosquitto`

## 9. Dependencies On Other Services

Primary in-repo consumer:

- `smart-home-service` in Kubernetes

## 10. Data / Storage

- broker data and logs use `emptyDir`
- no persistent volume is configured in the current manifest

## 11. Security Model

- anonymous access is disabled
- credentials are loaded from Kubernetes secret `jarvis-secrets`
- ACL restricts traffic to `jarvis/#`
- service is cluster-internal

## 12. How To Run / Test

Kubernetes path:

```bash
./jarvis-launch.sh
```

## 13. Implementation Status

Implemented infrastructure component, k8s-only.

## 14. Known Gaps / Caveats

- not part of the local process runtime
- current storage is ephemeral
- repo contains no dynamic broker management layer beyond the manifest/configmap
