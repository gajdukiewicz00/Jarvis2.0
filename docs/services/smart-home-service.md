# smart-home-service

## 1. Name

`smart-home-service`

## 2. Type

Backend smart-home service.

## 3. Purpose

Exposes a small smart-home device/action API and delegates actions to either a mock transport or an MQTT-backed transport.

## 4. Current Reality

The service is implemented, but the device catalog is hardcoded and state is kept in memory. It should be treated as a functional but narrow service, not a production-grade dynamic home-automation platform.

## 5. Entry Points

- Spring Boot app: `org.jarvis.smarthome.SmartHomeServiceApplication`
- REST base path: `/api/v1/smarthome`

## 6. Configuration

Main configuration source:

- `apps/smart-home-service/src/main/resources/application.yml`

Important settings include:

- server port `8086`
- `smarthome.provider`, default `mqtt`
- MQTT broker/client credentials
- optional RabbitMQ/Kafka config
- allowed action list

The local runtime scripts set `SMART_HOME_PROVIDER=mock` by default.

## 7. API / WebSocket Surface

REST endpoints:

- `GET /api/v1/smarthome/devices`
- `GET /api/v1/smarthome/devices/{deviceId}`
- `POST /api/v1/smarthome/devices/{deviceId}/action`
- `GET /api/v1/smarthome/actions`

No WebSocket endpoint.

## 8. Main Internal Components

- `SmartHomeController`
- `SmartHomeService`
- `SmartHomeDeviceCatalog`
- `MockSmartHomeTransport`
- `MqttSmartHomeTransport`

Static catalog entries confirmed in code:

- `kitchen_light`
- `desk_lamp`
- `hall_thermostat`
- `front_door_lock`

## 9. Dependencies On Other Services

No mandatory downstream Jarvis service dependency for core behavior. Optional transport dependency is an MQTT broker such as Mosquitto.

## 10. Data / Storage

- no database
- device definitions are hardcoded in code
- user/device state is stored in memory

## 11. Security Model

Protected by the standard shared security model. Service-to-service auth is supported.

## 12. How To Run / Test

Module test command:

```bash
mvn -pl apps/smart-home-service -am test
```

Runtime:

- local: `./scripts/runtime-up.sh`
- k8s: included in `k8s/base`

## 13. Implementation Status

Implemented with static catalog/in-memory state.

## 14. Known Gaps / Caveats

- Device inventory is fixed in code.
- No persistence layer for device state was confirmed.
- Local runtime default provider differs from application default (`mock` vs `mqtt`).
